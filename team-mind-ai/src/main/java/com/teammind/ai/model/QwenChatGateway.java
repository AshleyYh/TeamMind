package com.teammind.ai.model;

import com.teammind.ai.config.QwenProperties;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

@Component
public class QwenChatGateway implements ChatCompletionGateway {
    private final QwenProperties properties;
    private final RestClient client;

    public QwenChatGateway(QwenProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        this.client = builder.baseUrl(properties.baseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                .requestFactory(factory).build();
    }

    @Override
    public String generate(String systemPrompt, String userContent) {
        if (properties.apiKey().isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "未配置 QWEN_API_KEY，无法调用对话模型");
        }
        try {
            ChatResponse response = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", properties.chatModel(), "stream", false,
                            "messages", List.of(Map.of("role", "system", "content", systemPrompt),
                                    Map.of("role", "user", "content", userContent))))
                    .retrieve().body(ChatResponse.class);
            if (response == null || response.choices() == null || response.choices().isEmpty()
                    || response.choices().getFirst().message() == null
                    || response.choices().getFirst().message().content() == null
                    || response.choices().getFirst().message().content().isBlank()) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型未返回有效回答");
            }
            return response.choices().getFirst().message().content();
        } catch (RestClientResponseException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "对话模型服务调用失败", exception);
        } catch (org.springframework.web.client.ResourceAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "对话模型服务连接或响应超时", exception);
        } catch (org.springframework.web.client.RestClientException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "对话模型服务返回格式异常", exception);
        }
    }

    record ChatResponse(List<Choice> choices) {
    }

    record Choice(Message message) {
    }

    record Message(String content) {
    }
}
