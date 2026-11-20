package com.teammind.knowledge.infrastructure.search;

import com.sun.net.httpserver.HttpServer;
import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.config.RerankerProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashScopeRerankerGatewayTests {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 按协议提交候选并返回相关性分数() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/services/rerank/text-rerank/text-rerank", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.95}]}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var gateway = new DashScopeRerankerGateway(new RerankerProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1", "test-key",
                "qwen3.7-text-rerank",
                "Given a web search query, retrieve relevant passages that answer the query.",
                Duration.ofSeconds(2), Duration.ofSeconds(2)), RestClient.builder());
        var results = gateway.rerank("连接数", List.of(chunk(1), chunk(2)));
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.index()).isEqualTo(1);
            assertThat(result.score()).isEqualTo(0.95);
        });
        assertThat(request.get()).contains("qwen3.7-text-rerank", "内容2", "\"instruct\"",
                "\"top_n\":2").doesNotContain("return_documents");
    }

    @Test
    void 缺少密钥时明确失败而不发送请求() {
        var gateway = new DashScopeRerankerGateway(new RerankerProperties("http://localhost:9", "",
                "qwen3.7-text-rerank", "", Duration.ofSeconds(1), Duration.ofSeconds(1)), RestClient.builder());
        assertThatThrownBy(() -> gateway.rerank("问题", List.of(chunk(1))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("RERANK_API_KEY");
    }

    private RetrievedChunk chunk(long id) {
        return new RetrievedChunk(id, 1L, 1L, "研发.md", 1, 0, null, "内容" + id, null, null, null, null, null, 0d, null);
    }
}
