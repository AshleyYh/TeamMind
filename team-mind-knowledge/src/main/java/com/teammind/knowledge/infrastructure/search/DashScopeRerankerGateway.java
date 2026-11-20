package com.teammind.knowledge.infrastructure.search;

import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.api.RerankerGateway;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.config.RerankerProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DashScope 原生文本重排协议，支持 qwen3.7-text-rerank 及兼容模型。 */
@Component
public class DashScopeRerankerGateway implements RerankerGateway {
    private final RerankerProperties properties;
    private final RestClient client;

    public DashScopeRerankerGateway(RerankerProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        this.client = builder.baseUrl(properties.baseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.apiKey()).requestFactory(factory).build();
    }

    @Override
    public List<RerankResult> rerank(String query, List<RetrievedChunk> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (properties.apiKey().isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "未配置 RERANK_API_KEY 或 QWEN_API_KEY，无法重排序");
        }
        try {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("top_n", candidates.size());
            // qwen3.7-text-rerank 不支持 return_documents，但支持 instruct 区分问答与相似度任务。
            if (properties.model().startsWith("qwen3")) {
                parameters.put("instruct", properties.instruct());
            }
            RerankResponse response = client.post().uri("/services/rerank/text-rerank/text-rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", properties.model(),
                            "input", Map.of("query", query,
                                    "documents", candidates.stream().map(RetrievedChunk::content).toList()),
                            "parameters", parameters))
                    .retrieve().body(RerankResponse.class);
            if (response == null || response.output() == null || response.output().results() == null) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "重排序模型未返回有效结果");
            }
            return response.output().results().stream()
                    .filter(result -> result.index() >= 0 && result.index() < candidates.size()
                            && Double.isFinite(result.relevanceScore()))
                    .map(result -> new RerankResult(result.index(), result.relevanceScore())).toList();
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "重排序模型服务调用失败", exception);
        } catch (ResourceAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "重排序模型连接或响应超时", exception);
        } catch (RestClientException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "重排序模型返回格式异常", exception);
        }
    }

    record RerankResponse(Output output) {
    }

    record Output(List<Result> results) {
    }

    record Result(int index, @com.fasterxml.jackson.annotation.JsonProperty("relevance_score") double relevanceScore) {
    }
}
