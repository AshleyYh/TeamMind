package com.teammind.memory.domain;

/** 会话增量摘要。summarizedMessages 表示累计被摘要并裁剪的消息数。 */
public record ConversationSummary(String summary, int summarizedMessages, String updatedAt) {
}
