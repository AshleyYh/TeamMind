package com.teammind.web.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "teammind.qdrant")
public record QdrantProperties(
        @NotBlank String host,
        @Min(1) @Max(65535) int port,
        String apiKey,
        boolean useTls,
        Duration requestTimeout,
        Duration healthTimeout
) {
    public QdrantProperties {
        apiKey = apiKey == null ? "" : apiKey;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
        healthTimeout = healthTimeout == null ? Duration.ofSeconds(3) : healthTimeout;
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("teammind.qdrant.request-timeout must be positive");
        }
        if (healthTimeout.isNegative() || healthTimeout.isZero()) {
            throw new IllegalArgumentException("teammind.qdrant.health-timeout must be positive");
        }
    }
}
