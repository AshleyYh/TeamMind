package com.teammind.ai.agent.model;

import java.util.List;

/**
 * 一次模型调用请求。jsonObject 用于任务理解的结构化输出；tools 非空时启用 Function Calling。
 */
public record AgentModelRequest(
        String systemPrompt,
        List<AgentMessage> messages,
        List<AgentToolSpec> tools,
        boolean jsonObject) {

    public AgentModelRequest {
        tools = tools == null ? List.of() : List.copyOf(tools);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
