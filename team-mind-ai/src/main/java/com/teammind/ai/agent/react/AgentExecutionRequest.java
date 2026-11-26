package com.teammind.ai.agent.react;

import com.teammind.ai.agent.model.ConversationMessage;

import java.util.List;

/** Agent 执行请求，知识库和文档范围由 Controller 参数校验后传入。 */
public record AgentExecutionRequest(
        String message,
        List<Long> knowledgeBaseIds,
        Long ownerId,
        Integer topK,
        String conversationId,
        List<ConversationMessage> history,
        List<Long> documentIds) {

    public AgentExecutionRequest {
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        history = history == null ? List.of() : List.copyOf(history);
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
    }
}
