package com.teammind.knowledge.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import com.teammind.knowledge.api.SearchStrategy;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "teammind.knowledge.retrieval")
public record RetrievalProperties(
        @Min(1) @Max(100) int denseCandidateSize,
        @Min(1) @Max(100) int bm25CandidateSize,
        @Min(1) @Max(100) int rerankCandidateSize,
        SearchStrategy defaultStrategy,
        @Min(1) int rrfK,
        @Min(1) int maxTermCount,
        Duration parallelTimeout) {

    public RetrievalProperties {
        defaultStrategy = defaultStrategy == null ? SearchStrategy.HYBRID_RERANK : defaultStrategy;
        parallelTimeout = parallelTimeout == null ? Duration.ofSeconds(15) : parallelTimeout;
        if (parallelTimeout.isNegative() || parallelTimeout.isZero()) {
            throw new IllegalArgumentException("并行检索超时时间必须大于零");
        }
    }
}
