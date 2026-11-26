package com.teammind.ai.agent.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Agent 执行边界配置。所有限制都有明确上限，避免模型调用或工具执行无限循环。 */
@Validated
@ConfigurationProperties(prefix = "teammind.agent")
public record AgentProperties(
        @Min(1) @Max(20) int maxIterations,
        @Min(1) @Max(50) int maxToolCalls,
        @Min(1) @Max(10) int maxConsecutiveFailures,
        @Min(1) @Max(50) int maxEvidence,
        @Min(1) @Max(50) int maxHistoryMessages,
        @Min(100) @Max(8000) int maxQuestionLength,
        @Min(500) @Max(50000) int maxObservationChars,
        @Min(200) @Max(5000) int maxChunkChars,
        Duration executionTimeout,
        Duration toolTimeout) {

    public AgentProperties {
        executionTimeout = executionTimeout == null ? Duration.ofSeconds(90) : executionTimeout;
        toolTimeout = toolTimeout == null ? Duration.ofSeconds(20) : toolTimeout;
        if (executionTimeout.isNegative() || executionTimeout.isZero()) {
            throw new IllegalArgumentException("Agent 执行超时时间必须大于零");
        }
        if (toolTimeout.isNegative() || toolTimeout.isZero()) {
            throw new IllegalArgumentException("Agent 工具超时时间必须大于零");
        }
        if (toolTimeout.compareTo(executionTimeout) > 0) {
            throw new IllegalArgumentException("Agent 工具超时时间不能大于整体执行超时时间");
        }
    }
}
