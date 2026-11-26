package com.teammind.memory.domain;

/**
 * Redis 中的一条会话消息。traceId 用于把消息关联到一次 Agent 执行；
 * createdAt 使用 ISO 字符串，避免 Redis 序列化依赖具体时间模块配置。
 */
public record StoredMessage(String role, String content, String traceId, String createdAt) {
}
