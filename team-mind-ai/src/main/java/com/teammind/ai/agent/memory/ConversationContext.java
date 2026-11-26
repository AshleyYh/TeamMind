package com.teammind.ai.agent.memory;

import com.teammind.ai.agent.model.ConversationMessage;

import java.util.List;

/** Agent 使用的一次会话上下文：增量摘要 + 最近消息 + 记忆降级警告。 */
public record ConversationContext(
        String conversationId,
        String summary,
        List<ConversationMessage> messages,
        List<String> warnings) {

    public ConversationContext {
        messages = messages == null ? List.of() : List.copyOf(messages);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ConversationContext fallback(String conversationId, List<ConversationMessage> messages) {
        return new ConversationContext(conversationId, null, messages, List.of());
    }
}
