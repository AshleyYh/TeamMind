package com.teammind.ai.model;

import com.teammind.ai.config.QwenProperties;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.api.EmbeddingGateway;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

@Component
public class QwenEmbeddingGateway implements EmbeddingGateway {
    private final QwenProperties properties;
    private final RestClient client;

    public QwenEmbeddingGateway(QwenProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout()).build());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.client = builder.baseUrl(properties.baseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                .requestFactory(requestFactory).build();
    }

    @Override
    public List<List<Float>> embedDocuments(List<String> texts) {
        return embed(texts);
    }

    @Override
    public List<Float> embedQuery(String text) {
        return embed(List.of(text)).getFirst();
    }

    @Override
    public int dimension() {
        return properties.embeddingDimension();
    }

    private List<List<Float>> embed(List<String> texts) {
        if (texts.isEmpty() || texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "向量化文本不能为空");
        }
        ensureConfigured();
        try {
            EmbeddingResponse response = client.post().uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", properties.embeddingModel(), "input", texts,
                            "dimensions", dimension()))
                    .retrieve().body(EmbeddingResponse.class);
            if (response == null || response.data() == null || response.data().size() != texts.size()) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Embedding 服务返回的数据不完整");
            }
            List<EmbeddingData> ordered = response.data().stream()
                    .sorted(java.util.Comparator.comparingInt(EmbeddingData::index)).toList();
            for (int i = 0; i < ordered.size(); i++) {
                if (ordered.get(i).index() != i || ordered.get(i).embedding() == null
                        || ordered.get(i).embedding().size() != dimension()) {
                    throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Embedding 返回顺序或维度不符合约定");
                }
            }
            return ordered.stream().map(EmbeddingData::embedding).toList();
        } catch (RestClientResponseException exception) {
            // 不把远程响应体写入业务错误，避免密钥或敏感文本泄漏。
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Embedding 服务调用失败", exception);
        } catch (org.springframework.web.client.ResourceAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Embedding 服务连接或响应超时", exception);
        } catch (org.springframework.web.client.RestClientException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Embedding 服务返回格式异常", exception);
        }
    }

    private void ensureConfigured() {
        if (properties.apiKey().isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "未配置 QWEN_API_KEY，无法调用 Embedding 服务");
        }
    }

    record EmbeddingResponse(List<EmbeddingData> data) {
    }

    record EmbeddingData(List<Float> embedding, int index) {
    }
}
