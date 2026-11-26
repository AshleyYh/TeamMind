package com.teammind.ai.agent.model;

/** 模型发起的一次函数调用；参数保持原始 JSON 字符串，执行前必须由工具层再次校验。 */
public record AgentToolCall(String id, String name, String arguments) {
}
