package com.teammind.memory.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 会话与长期记忆边界配置。所有窗口都有上限，避免 Redis 和提示词无限增长。 */
@Validated
@ConfigurationProperties(prefix = "teammind.memory")
public record MemoryProperties(
        Duration conversationTtl,
        @Min(10) @Max(5000) int maxMessages,
        @Min(2) @Max(100) int contextMessages,
        @Min(10) @Max(5000) int summaryThreshold,
        @Min(200) @Max(20000) int maxSummaryChars,
        @Min(1) @Max(50) int maxSearchResults,
        @Min(1) @Max(20) int maxExtractionCandidates) {

    public MemoryProperties {
        conversationTtl = conversationTtl == null ? Duration.ofDays(7) : conversationTtl;
        if (conversationTtl.isNegative() || conversationTtl.isZero()) {
            throw new IllegalArgumentException("会话记忆过期时间必须大于零");
        }
        if (contextMessages > maxMessages) {
            throw new IllegalArgumentException("上下文消息数不能大于最大消息数");
        }
        if (summaryThreshold > maxMessages) {
            throw new IllegalArgumentException("摘要阈值不能大于最大消息数");
        }
    }
}
