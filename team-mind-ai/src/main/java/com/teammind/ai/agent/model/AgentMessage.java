package com.teammind.ai.agent.model;

import java.util.List;

/** OpenAI 兼容协议中的一条消息，支持 assistant 工具调用与 tool 结果回填。 */
public record AgentMessage(
        String role,
        String content,
        String toolCallId,
        String name,
        List<AgentToolCall> toolCalls) {

    public AgentMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static AgentMessage system(String content) {
        return new AgentMessage("system", content, null, null, List.of());
    }

    public static AgentMessage user(String content) {
        return new AgentMessage("user", content, null, null, List.of());
    }

    public static AgentMessage assistant(String content) {
        return new AgentMessage("assistant", content, null, null, List.of());
    }

    public static AgentMessage assistantToolCalls(String content, List<AgentToolCall> toolCalls) {
        return new AgentMessage("assistant", content, null, null, toolCalls);
    }

    public static AgentMessage tool(String toolCallId, String content) {
        return new AgentMessage("tool", content, toolCallId, null, List.of());
    }
}
