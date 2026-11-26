package com.teammind.ai.agent.model;

import java.util.List;

/** 模型返回内容。content 可能为空，此时应结合 toolCalls 判断是否继续执行。 */
public record AgentModelResponse(
        String content,
        List<AgentToolCall> toolCalls,
        String finishReason,
        ModelUsage usage) {

    public AgentModelResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? ModelUsage.empty() : usage;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
