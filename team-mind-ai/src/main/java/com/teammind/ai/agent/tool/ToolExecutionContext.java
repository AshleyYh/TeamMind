package com.teammind.ai.agent.tool;

import com.teammind.ai.agent.model.ConversationMessage;

import java.util.List;

/**
 * 工具执行上下文。知识库/文档范围、归属和历史都来自服务端已校验的请求，
 * 模型参数只能在这个范围内进一步收窄，不能扩大。
 */
public record ToolExecutionContext(
        String traceId,
        Long ownerId,
        List<Long> allowedKnowledgeBaseIds,
        List<Long> allowedDocumentIds,
        List<ConversationMessage> history,
        EvidenceCollector evidence,
        int defaultTopK) {

    public ToolExecutionContext {
        allowedKnowledgeBaseIds = allowedKnowledgeBaseIds == null ? List.of() : List.copyOf(allowedKnowledgeBaseIds);
        allowedDocumentIds = allowedDocumentIds == null ? List.of() : List.copyOf(allowedDocumentIds);
        history = history == null ? List.of() : List.copyOf(history);
        defaultTopK = Math.min(Math.max(defaultTopK, 1), 20);
    }
}
