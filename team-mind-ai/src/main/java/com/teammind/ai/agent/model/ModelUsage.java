package com.teammind.ai.agent.model;

/** 单次模型调用的 Token 用量，Phase 4 仅透传和记录，完整统计属于 Phase 6。 */
public record ModelUsage(int inputTokens, int outputTokens) {

    public static ModelUsage empty() {
        return new ModelUsage(0, 0);
    }

    public ModelUsage plus(ModelUsage other) {
        if (other == null) {
            return this;
        }
        return new ModelUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }
}
