package com.teammind.ai.agent.task;

import com.teammind.ai.agent.model.ConversationMessage;

import java.util.List;

/** 任务理解所需的请求上下文，所有知识库和文档范围都来自服务端已校验的请求。 */
public record TaskContext(
        String question,
        Long ownerId,
        List<Long> allowedKnowledgeBaseIds,
        List<Long> allowedDocumentIds,
        List<ConversationMessage> history,
        String conversationSummary) {

    public TaskContext {
        allowedKnowledgeBaseIds = allowedKnowledgeBaseIds == null ? List.of() : List.copyOf(allowedKnowledgeBaseIds);
        allowedDocumentIds = allowedDocumentIds == null ? List.of() : List.copyOf(allowedDocumentIds);
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** 兼容 Phase 4 的无摘要构造方式。 */
    public TaskContext(String question, Long ownerId, List<Long> allowedKnowledgeBaseIds,
            List<Long> allowedDocumentIds, List<ConversationMessage> history) {
        this(question, ownerId, allowedKnowledgeBaseIds, allowedDocumentIds, history, null);
    }
}
