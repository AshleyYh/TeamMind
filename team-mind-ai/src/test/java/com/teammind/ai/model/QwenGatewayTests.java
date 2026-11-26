package com.teammind.ai.model;

import com.teammind.ai.config.QwenProperties;
import com.teammind.common.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QwenGatewayTests {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void Embedding按索引返回且校验向量维度() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}");
        });
        server.start();
        QwenEmbeddingGateway gateway = new QwenEmbeddingGateway(properties(), RestClient.builder());

        assertThat(gateway.embedQuery("订单模块")).containsExactly(0.1F, 0.2F);
        assertThat(request.get()).contains("test-embedding", "订单模块", "\"dimensions\":2");
    }

    @Test
    void Embedding维度错误时拒绝入库() throws Exception {
        startServer("/embeddings", 200, "{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}");
        QwenEmbeddingGateway gateway = new QwenEmbeddingGateway(properties(), RestClient.builder());
        assertThatThrownBy(() -> gateway.embedQuery("测试"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("维度");
    }

    @Test
    void 对话模型返回有效内容() throws Exception {
        startServer("/chat/completions", 200,
                "{\"choices\":[{\"message\":{\"content\":\"最大连接数为30 [1]\"}}]}");
        QwenChatGateway gateway = new QwenChatGateway(properties(), RestClient.builder());
        assertThat(gateway.generate("系统规则", "用户问题")).isEqualTo("最大连接数为30 [1]");
    }

    @Test
    void 对话模型解析Token用量() throws Exception {
        startServer("/chat/completions", 200, """
                {"choices":[{"message":{"content":"最大连接数为30 [1]"}}],
                 "usage":{"prompt_tokens":11,"completion_tokens":7}}
                """);
        QwenChatGateway gateway = new QwenChatGateway(properties(), RestClient.builder());

        var result = gateway.generateWithUsage("系统规则", "用户问题");

        assertThat(result.content()).isEqualTo("最大连接数为30 [1]");
        assertThat(result.inputTokens()).isEqualTo(11);
        assertThat(result.outputTokens()).isEqualTo(7);
    }

    private void startServer(String path, int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> respond(exchange, status, body));
        server.start();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private QwenProperties properties() {
        return new QwenProperties("http://127.0.0.1:" + server.getAddress().getPort(), "test-key",
                "test-embedding", 2, "test-chat", Duration.ofSeconds(2), Duration.ofSeconds(2));
    }
}
