package com.teammind.ai.agent.model;

/** 请求携带的历史消息。Phase 4 不持久化会话，只使用本次请求内的上下文。 */
public record ConversationMessage(String role, String content) {
}
