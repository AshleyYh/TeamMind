package com.teammind.ai.agent.tool.impl;

import com.teammind.ai.agent.tool.AgentTool;
import com.teammind.ai.agent.tool.ToolArguments;
import com.teammind.ai.agent.tool.ToolExecutionContext;
import com.teammind.ai.agent.tool.ToolResult;
import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.application.DocumentService;
import com.teammind.knowledge.domain.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 文档元信息工具：只返回文档名称、版本、状态等，不暴露服务器文件路径。 */
@Component
public class DocumentDetailTool implements AgentTool {
    private final DocumentService documentService;

    public DocumentDetailTool(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public String name() {
        return "getDocumentDetail";
    }

    @Override
    public String description() {
        return "获取指定文档的名称、版本、类型、处理状态和更新时间等元信息。不返回文件内容。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "documentId", Map.of("type", "integer", "description", "文档编号", "minimum", 1)),
                "required", List.of("documentId"),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> rawArguments) {
        ToolArguments arguments = ToolArguments.of(rawArguments);
        arguments.rejectUnknown("documentId");
        Long documentId = arguments.requireLong("documentId", 1, Long.MAX_VALUE);
        if (!context.allowedDocumentIds().isEmpty() && !context.allowedDocumentIds().contains(documentId)) {
            return ToolResult.failure("指定文档超出当前请求的文档范围。", Map.of("error", "OUT_OF_SCOPE"));
        }
        try {
            Document document = documentService.get(documentId, context.ownerId());
            String content = """
                    文档《%s》
                    - 文档编号：%d
                    - 所属知识库：%d
                    - 文件类型：%s
                    - 当前版本：%s
                    - 处理状态：%s
                    - 失败原因：%s
                    - 创建时间：%s
                    - 更新时间：%s
                    """.formatted(document.getDocumentName(), document.getId(), document.getKnowledgeBaseId(),
                    document.getFileType(), document.getCurrentVersion(), document.getProcessingStatus(),
                    document.getFailureReason() == null ? "无" : document.getFailureReason(),
                    document.getCreatedAt(), document.getUpdatedAt());
            return ToolResult.success(content, Map.of(
                    "documentId", document.getId(),
                    "knowledgeBaseId", document.getKnowledgeBaseId(),
                    "documentName", document.getDocumentName(),
                    "status", document.getProcessingStatus(),
                    "version", document.getCurrentVersion()));
        } catch (BusinessException exception) {
            return ToolResult.failure("指定的文档不存在或不可访问。", Map.of("error", "DOCUMENT_NOT_FOUND"));
        }
    }
}
