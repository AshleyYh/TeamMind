package com.teammind.ai.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teammind.ai.config.QwenProperties;
import com.teammind.common.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QwenAgentModelGatewayTests {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 发送工具声明并解析工具调用() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"choices":[{"message":{"content":null,"tool_calls":[
                      {"id":"call_1","type":"function","function":{"name":"knowledgeSearch",
                       "arguments":"{\\"query\\":\\"连接池\\"}"}}
                    ]},"finish_reason":"tool_calls"}],
                     "usage":{"prompt_tokens":10,"completion_tokens":5}}
                    """);
        });
        QwenAgentModelGateway gateway = gateway(properties("test-key"));

        AgentModelResponse response = gateway.complete(new AgentModelRequest("系统规则",
                List.of(AgentMessage.user("最大连接数？")),
                List.of(new AgentToolSpec("knowledgeSearch", "检索知识库",
                        Map.of("type", "object", "properties", Map.of()))), false));

        assertThat(requestBody.get()).contains("\"tools\"", "knowledgeSearch", "\"tool_choice\":\"auto\"");
        assertThat(response.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call_1");
            assertThat(call.name()).isEqualTo("knowledgeSearch");
            assertThat(call.arguments()).contains("连接池");
        });
        assertThat(response.usage().inputTokens()).isEqualTo(10);
        assertThat(response.usage().outputTokens()).isEqualTo(5);
    }

    @Test
    void JSON模式请求包含response_format() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"choices":[{"message":{"content":"{\\"taskType\\":\\"GENERAL_CHAT\\"}"},"finish_reason":"stop"}]}
                    """);
        });
        QwenAgentModelGateway gateway = gateway(properties("test-key"));

        AgentModelResponse response = gateway.complete(new AgentModelRequest("任务理解",
                List.of(AgentMessage.user("你好")), List.of(), true));

        assertThat(requestBody.get()).contains("\"response_format\":{\"type\":\"json_object\"}");
        assertThat(response.content()).contains("GENERAL_CHAT");
    }

    @Test
    void 流式响应按增量回调并累计文本() throws Exception {
        startServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            String body = """
                    data: {"choices":[{"delta":{"content":"最大连接数"},"finish_reason":null}]}

                    data: {"choices":[{"delta":{"content":"为 30 [1]"},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":4}}

                    data: [DONE]

                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        QwenAgentModelGateway gateway = gateway(properties("test-key"));
        StringBuilder deltas = new StringBuilder();

        AgentModelResponse response = gateway.stream(new AgentModelRequest("系统规则",
                List.of(AgentMessage.user("最大连接数？")), List.of(), false), deltas::append);

        assertThat(deltas.toString()).isEqualTo("最大连接数为 30 [1]");
        assertThat(response.content()).isEqualTo("最大连接数为 30 [1]");
        assertThat(response.usage().inputTokens()).isEqualTo(8);
    }

    @Test
    void HTTP错误和缺少密钥都返回稳定业务异常() throws Exception {
        startServer(exchange -> respond(exchange, 500, "{\"error\":\"internal\"}"));
        QwenAgentModelGateway gateway = gateway(properties("test-key"));

        assertThatThrownBy(() -> gateway.complete(new AgentModelRequest("系统",
                List.of(AgentMessage.user("你好")), List.of(), false)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("调用失败");

        QwenAgentModelGateway noKeyGateway = gateway(properties(""));
        assertThatThrownBy(() -> noKeyGateway.complete(new AgentModelRequest("系统",
                List.of(AgentMessage.user("你好")), List.of(), false)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("QWEN_API_KEY");
    }

    @Test
    void 模型响应超时返回稳定业务异常() throws Exception {
        startServer(exchange -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, """
                    {"choices":[{"message":{"content":"迟到"},"finish_reason":"stop"}]}
                    """);
        });
        QwenProperties properties = new QwenProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key",
                "test-embedding", 2, "test-chat", Duration.ofMillis(100), Duration.ofMillis(100));
        QwenAgentModelGateway gateway = gateway(properties);

        assertThatThrownBy(() -> gateway.complete(new AgentModelRequest("系统",
                List.of(AgentMessage.user("你好")), List.of(), false)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("超时");
    }

    @Test
    void 流式接口返回非2xx时失败() throws Exception {
        startServer(exchange -> respond(exchange, 500, "{\"error\":\"internal\"}"));
        QwenAgentModelGateway gateway = gateway(properties("test-key"));

        assertThatThrownBy(() -> gateway.stream(new AgentModelRequest("系统",
                List.of(AgentMessage.user("你好")), List.of(), false), delta -> {
        })).isInstanceOf(BusinessException.class);
    }

    private void startServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", handler);
        server.start();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private QwenAgentModelGateway gateway(QwenProperties properties) {
        return new QwenAgentModelGateway(properties, new ObjectMapper(), RestClient.builder());
    }

    private QwenProperties properties(String apiKey) {
        return new QwenProperties("http://127.0.0.1:" + server.getAddress().getPort(), apiKey,
                "test-embedding", 2, "test-chat", Duration.ofSeconds(2), Duration.ofSeconds(5));
    }
}
