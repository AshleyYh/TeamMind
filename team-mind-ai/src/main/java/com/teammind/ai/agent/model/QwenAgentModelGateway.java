package com.teammind.ai.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.teammind.ai.config.QwenProperties;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 基于 DashScope Qwen OpenAI 兼容接口的 Agent 模型网关。
 *
 * <p>任务理解使用 JSON 模式，ReAct 循环使用原生 Function Calling，最终答案使用 SSE 流式输出。
 * 模型返回的工具名、参数和引用编号都不能直接信任，调用方仍需做白名单和范围校验。</p>
 */
@Component
public class QwenAgentModelGateway implements AgentModelGateway {
    private static final Logger log = LoggerFactory.getLogger(QwenAgentModelGateway.class);

    private final QwenProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public QwenAgentModelGateway(QwenProperties properties, ObjectMapper objectMapper,
            RestClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        this.client = builder.baseUrl(properties.baseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                .requestFactory(factory).build();
    }

    @Override
    public AgentModelResponse complete(AgentModelRequest request) {
        requireApiKey();
        try {
            JsonNode response = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(request, false))
                    .retrieve().body(JsonNode.class);
            return parseResponse(response, request.jsonObject());
        } catch (RestClientResponseException exception) {
            throw modelHttpFailure(exception);
        } catch (ResourceAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "对话模型服务连接或响应超时", exception);
        } catch (RestClientException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "对话模型服务返回格式异常", exception);
        }
    }

    @Override
    public AgentModelResponse stream(AgentModelRequest request, Consumer<String> deltaConsumer) {
        requireApiKey();
        try {
            AgentModelResponse response = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(request, true))
                    .exchange((httpRequest, httpResponse) -> readStream(httpResponse, deltaConsumer));
            if (response == null || response.content() == null || response.content().isBlank()) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型未返回有效回答");
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw modelHttpFailure(exception);
        } catch (ResourceAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "对话模型流式调用连接或响应超时", exception);
        } catch (RestClientException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "对话模型流式响应格式异常", exception);
        }
    }

    private AgentModelResponse readStream(org.springframework.http.client.ClientHttpResponse response,
            Consumer<String> deltaConsumer) throws IOException {
        if (!response.getStatusCode().is2xxSuccessful()) {
            String errorBody = readLimited(response.getBody());
            log.warn("对话模型流式调用失败：status={}, body={}", response.getStatusCode().value(),
                    abbreviate(errorBody));
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "对话模型流式调用失败");
        }
        StringBuilder content = new StringBuilder();
        ModelUsage usage = ModelUsage.empty();
        String finishReason = null;
        InputStream body = response.getBody();
        if (body == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "对话模型未返回流式响应");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || !line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode chunk = objectMapper.readTree(data);
                JsonNode choices = chunk.path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    JsonNode choice = choices.get(0);
                    JsonNode delta = choice.path("delta").path("content");
                    if (delta.isTextual() && !delta.asText().isEmpty()) {
                        String text = delta.asText();
                        content.append(text);
                        if (deltaConsumer != null) {
                            deltaConsumer.accept(text);
                        }
                    }
                    if (choice.hasNonNull("finish_reason")) {
                        finishReason = choice.get("finish_reason").asText();
                    }
                }
                usage = usage.plus(parseUsage(chunk));
            }
        }
        return new AgentModelResponse(content.toString(), List.of(), finishReason, usage);
    }

    private ObjectNode requestBody(AgentModelRequest request, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.chatModel());
        body.put("stream", stream);
        ArrayNode messages = body.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        for (AgentMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            if ("assistant".equals(message.role()) && !message.toolCalls().isEmpty()) {
                // OpenAI 兼容协议允许 assistant 同时带 content 与 tool_calls，content 可为空。
                if (message.content() == null) {
                    node.putNull("content");
                } else {
                    node.put("content", message.content());
                }
                ArrayNode calls = node.putArray("tool_calls");
                for (AgentToolCall call : message.toolCalls()) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    callNode.putObject("function").put("name", call.name()).put("arguments", call.arguments());
                }
            } else {
                node.put("content", message.content() == null ? "" : message.content());
                if (message.toolCallId() != null) {
                    node.put("tool_call_id", message.toolCallId());
                }
                if (message.name() != null) {
                    node.put("name", message.name());
                }
            }
        }
        if (request.jsonObject()) {
            body.putObject("response_format").put("type", "json_object");
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (AgentToolSpec spec : request.tools()) {
                ObjectNode toolNode = tools.addObject();
                toolNode.put("type", "function");
                ObjectNode function = toolNode.putObject("function");
                function.put("name", spec.name());
                function.put("description", spec.description());
                function.set("parameters", objectMapper.valueToTree(
                        spec.parameters() == null ? Map.of("type", "object", "properties", Map.of()) : spec.parameters()));
            }
            body.put("tool_choice", "auto");
        }
        return body;
    }

    private AgentModelResponse parseResponse(JsonNode root, boolean jsonExpected) {
        if (root == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型未返回有效回答");
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty() || !choices.get(0).has("message")) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "对话模型服务返回格式异常");
        }
        JsonNode message = choices.get(0).path("message");
        String content = message.hasNonNull("content") ? message.get("content").asText() : null;
        List<AgentToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));
        if ((content == null || content.isBlank()) && toolCalls.isEmpty()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型未返回有效回答");
        }
        if (jsonExpected && toolCalls.isEmpty() && (content == null || content.isBlank())) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型未返回结构化结果");
        }
        String finishReason = choices.get(0).hasNonNull("finish_reason")
                ? choices.get(0).get("finish_reason").asText() : null;
        return new AgentModelResponse(content, toolCalls, finishReason, parseUsage(root));
    }

    private List<AgentToolCall> parseToolCalls(JsonNode callsNode) {
        if (!callsNode.isArray()) {
            return List.of();
        }
        List<AgentToolCall> calls = new ArrayList<>();
        for (JsonNode node : callsNode) {
            JsonNode function = node.path("function");
            String name = function.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String id = node.path("id").asText("").trim();
            if (id.isEmpty()) {
                // 少数兼容服务不返回调用 ID，这里生成稳定 ID 以便回填 tool 消息。
                id = "call_" + UUID.randomUUID().toString().replace("-", "");
            }
            JsonNode argumentsNode = function.path("arguments");
            String arguments = argumentsNode.isTextual()
                    ? argumentsNode.asText() : argumentsNode.isMissingNode() ? "{}" : argumentsNode.toString();
            calls.add(new AgentToolCall(id, name, arguments));
        }
        return List.copyOf(calls);
    }

    private ModelUsage parseUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (!usage.isObject()) {
            return ModelUsage.empty();
        }
        return new ModelUsage(usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0));
    }

    private void requireApiKey() {
        if (properties.apiKey().isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "未配置 QWEN_API_KEY，无法调用 Agent 模型");
        }
    }

    private BusinessException modelHttpFailure(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        log.warn("对话模型 HTTP 调用失败：status={}, body={}", status,
                abbreviate(exception.getResponseBodyAsString()));
        String message = switch (status) {
            case 401, 403 -> "对话模型鉴权失败，请检查 QWEN_API_KEY";
            case 429 -> "对话模型调用频率受限，请稍后重试";
            default -> "对话模型服务调用失败";
        };
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, message, exception);
    }

    private String readLimited(InputStream stream) {
        if (stream == null) {
            return "";
        }
        try {
            return new String(stream.readNBytes(2048), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "";
        }
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }
}
