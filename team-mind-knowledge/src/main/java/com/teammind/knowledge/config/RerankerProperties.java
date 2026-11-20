package com.teammind.knowledge.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "teammind.knowledge.reranker")
public record RerankerProperties(@NotBlank String baseUrl, String apiKey, @NotBlank String model,
        String instruct, Duration connectTimeout, Duration readTimeout) {
    public RerankerProperties {
        apiKey = apiKey == null ? "" : apiKey;
        instruct = instruct == null || instruct.isBlank()
                ? "Given a web search query, retrieve relevant passages that answer the query." : instruct;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("重排序模型超时时间必须大于零");
        }
    }
}
