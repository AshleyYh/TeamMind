package com.teammind.ai.agent.memory;

import com.teammind.ai.agent.model.ConversationMessage;
import com.teammind.ai.agent.model.ModelUsage;

import java.util.List;

/** Agent 使用的一次会话上下文：增量摘要 + 最近消息 + 记忆降级警告 + 摘要模型用量。 */
public record ConversationContext(
        String conversationId,
        String summary,
        List<ConversationMessage> messages,
        List<String> warnings,
        ModelUsage usage) {

    public ConversationContext {
        messages = messages == null ? List.of() : List.copyOf(messages);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        usage = usage == null ? ModelUsage.empty() : usage;
    }

    /** 兼容没有额外模型调用的上下文。 */
    public ConversationContext(String conversationId, String summary, List<ConversationMessage> messages,
            List<String> warnings) {
        this(conversationId, summary, messages, warnings, ModelUsage.empty());
    }

    public static ConversationContext fallback(String conversationId, List<ConversationMessage> messages) {
        return new ConversationContext(conversationId, null, messages, List.of(), ModelUsage.empty());
    }
}
