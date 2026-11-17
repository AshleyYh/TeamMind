package com.teammind.ai.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "teammind.ai.qwen")
public record QwenProperties(
        @NotBlank String baseUrl,
        String apiKey,
        @NotBlank String embeddingModel,
        @Min(1) int embeddingDimension,
        @NotBlank String chatModel,
        Duration connectTimeout,
        Duration readTimeout) {

    public QwenProperties {
        apiKey = apiKey == null ? "" : apiKey;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(60) : readTimeout;
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("模型客户端超时时间必须大于零");
        }
    }
}
