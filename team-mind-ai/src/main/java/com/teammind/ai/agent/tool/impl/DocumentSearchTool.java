package com.teammind.ai.agent.tool.impl;

import com.teammind.ai.agent.config.AgentProperties;
import com.teammind.ai.agent.tool.AgentTool;
import com.teammind.ai.agent.tool.ToolArguments;
import com.teammind.ai.agent.tool.ToolExecutionContext;
import com.teammind.ai.agent.tool.ToolResult;
import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.api.RetrievalResult;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.application.DocumentService;
import com.teammind.knowledge.application.KnowledgeSearchService;
import com.teammind.knowledge.domain.Document;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指定文档检索工具。先校验文档归属，再在所属知识库中检索并按文档 ID 过滤。
 * 由于底层检索暂不支持按文档下推过滤，这里扩大候选集后精确过滤，结果数量有上限。
 */
@Component
public class DocumentSearchTool implements AgentTool {
    private final KnowledgeSearchService searchService;
    private final DocumentService documentService;
    private final AgentProperties properties;

    public DocumentSearchTool(KnowledgeSearchService searchService, DocumentService documentService,
            AgentProperties properties) {
        this.searchService = searchService;
        this.documentService = documentService;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "documentSearch";
    }

    @Override
    public String description() {
        return "在用户指定的文档范围内检索证据。需要查找特定文档中的内容时使用，必须提供文档编号。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "检索问题", "maxLength", 1000),
                        "documentIds", Map.of("type", "array", "description", "限定文档编号",
                                "items", Map.of("type", "integer"), "minItems", 1, "maxItems", 20),
                        "topK", Map.of("type", "integer", "description", "返回证据条数",
                                "minimum", 1, "maximum", 10)),
                "required", List.of("query", "documentIds"),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> rawArguments) {
        ToolArguments arguments = ToolArguments.of(rawArguments);
        arguments.rejectUnknown("query", "documentIds", "topK");
        String query = arguments.requireString("query", 1000);
        List<Long> documentIds = arguments.requireLongList("documentIds", 20);
        Integer requestedTopK = arguments.optionalInt("topK", 1, 10);
        int topK = requestedTopK == null ? context.defaultTopK() : requestedTopK;
        if (!context.allowedDocumentIds().isEmpty()
                && !context.allowedDocumentIds().containsAll(documentIds)) {
            return ToolResult.failure("指定文档超出当前请求的文档范围。", Map.of("error", "OUT_OF_SCOPE"));
        }

        LinkedHashSet<Long> knowledgeBaseIds = new LinkedHashSet<>();
        try {
            for (Long documentId : documentIds) {
                Document document = documentService.get(documentId, context.ownerId());
                knowledgeBaseIds.add(document.getKnowledgeBaseId());
            }
        } catch (BusinessException exception) {
            // 不区分“无权限”和“不存在”，避免模型或用户枚举文档编号。
            return ToolResult.failure("指定的文档不存在或不可访问。", Map.of("error", "DOCUMENT_NOT_FOUND"));
        }
        if (!context.allowedKnowledgeBaseIds().isEmpty()
                && !context.allowedKnowledgeBaseIds().containsAll(knowledgeBaseIds)) {
            return ToolResult.failure("指定文档超出当前知识库访问范围。", Map.of("error", "OUT_OF_SCOPE"));
        }

        // 检索层当前不支持文档过滤，扩大候选集后再按文档 ID 精确过滤。
        int candidateSize = Math.min(20, Math.max(topK * 4, topK));
        RetrievalResult result = searchService.searchWithTrace(query, List.copyOf(knowledgeBaseIds),
                context.ownerId(), candidateSize, null, context.traceId());
        Set<Long> documentIdSet = Set.copyOf(documentIds);
        List<RetrievedChunk> matched = result.chunks().stream()
                .filter(chunk -> documentIdSet.contains(chunk.documentId())).limit(topK).toList();
        if (matched.isEmpty()) {
            return ToolResult.success("指定文档中没有检索到与问题相关的证据。", Map.of(
                    "count", 0, "documentIds", documentIds));
        }
        StringBuilder content = new StringBuilder("指定文档检索结果：\n");
        int rendered = 0;
        for (RetrievedChunk chunk : matched) {
            Integer index = context.evidence().add(chunk);
            if (index == null) {
                continue;
            }
            content.append('\n').append(context.evidence().render(index, chunk, properties.maxChunkChars()))
                    .append('\n');
            rendered++;
        }
        return ToolResult.success(content.toString(), Map.of(
                "count", rendered, "documentIds", documentIds));
    }
}
