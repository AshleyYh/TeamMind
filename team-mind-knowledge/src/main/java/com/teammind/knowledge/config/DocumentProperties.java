package com.teammind.knowledge.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "teammind.document")
public record DocumentProperties(
        @Min(64) @Max(2000) int chunkTokens,
        @Min(0) int overlapTokens,
        @Min(1) @Max(100) int embeddingBatchSize,
        @Min(1) @Max(10000) int maxChunks,
        @Min(1) @Max(104857600) long maxFileBytes) {

    public DocumentProperties {
        if (overlapTokens >= chunkTokens) {
            throw new IllegalArgumentException("分块重叠 Token 数必须小于分块大小");
        }
    }
}
