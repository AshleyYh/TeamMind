package com.teammind.knowledge.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "teammind.knowledge.vector")
public record QdrantKnowledgeProperties(
        @NotBlank String collection,
        @Min(1) @Max(65536) int dimension,
        @Min(1) @Max(100) int topK,
        @Min(0) @Max(1) double scoreThreshold) {
}
