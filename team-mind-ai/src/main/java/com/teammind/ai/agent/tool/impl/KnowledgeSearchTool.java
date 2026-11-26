package com.teammind.ai.agent.tool.impl;

import com.teammind.ai.agent.config.AgentProperties;
import com.teammind.ai.agent.tool.AgentTool;
import com.teammind.ai.agent.tool.ToolArguments;
import com.teammind.ai.agent.tool.ToolExecutionContext;
import com.teammind.ai.agent.tool.ToolResult;
import com.teammind.knowledge.api.RetrievalResult;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.api.SearchStrategy;
import com.teammind.knowledge.application.KnowledgeSearchService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 知识库检索工具：复用 Phase 3 的混合检索、重排和 Evidence 写入。 */
@Component
public class KnowledgeSearchTool implements AgentTool {
    private final KnowledgeSearchService searchService;
    private final AgentProperties properties;

    public KnowledgeSearchTool(KnowledgeSearchService searchService, AgentProperties properties) {
        this.searchService = searchService;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "knowledgeSearch";
    }

    @Override
    public String description() {
        return "在用户可访问的知识库中检索与问题相关的证据。需要事实、方案、结论或引用时优先使用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "检索问题", "maxLength", 1000),
                        "topK", Map.of("type", "integer", "description", "返回证据条数", "minimum", 1, "maximum", 20),
                        "strategy", Map.of("type", "string", "description", "检索策略，默认使用服务端配置",
                                "enum", List.of("DENSE", "BM25", "HYBRID", "HYBRID_RERANK")),
                        "knowledgeBaseIds", Map.of("type", "array", "description", "收窄知识库范围",
                                "items", Map.of("type", "integer"), "maxItems", 20)),
                "required", List.of("query"),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> rawArguments) {
        ToolArguments arguments = ToolArguments.of(rawArguments);
        arguments.rejectUnknown("query", "topK", "strategy", "knowledgeBaseIds");
        String query = arguments.requireString("query", 1000);
        Integer topK = arguments.optionalInt("topK", 1, 20);
        SearchStrategy strategy = arguments.optionalEnum("strategy", SearchStrategy.class);
        List<Long> requested = arguments.optionalLongList("knowledgeBaseIds", 20);

        List<Long> allowed = context.allowedKnowledgeBaseIds();
        if (allowed.isEmpty()) {
            return ToolResult.failure("当前请求未指定可访问的知识库，无法执行知识库检索。",
                    Map.of("error", "NO_KNOWLEDGE_BASE"));
        }
        List<Long> scope = requested == null || requested.isEmpty() ? allowed : requested;
        if (!allowed.containsAll(scope)) {
            // 模型只能收窄范围，不能通过工具参数访问请求范围外的知识库。
            return ToolResult.failure("请求的知识库超出当前访问范围。", Map.of("error", "OUT_OF_SCOPE"));
        }

        RetrievalResult result = searchService.searchWithTrace(query, scope, context.ownerId(),
                topK == null ? context.defaultTopK() : topK, strategy, context.traceId());
        if (result.chunks().isEmpty()) {
            return ToolResult.success("知识库中没有检索到与问题相关的证据。", Map.of(
                    "count", 0, "strategy", result.strategy().name(), "knowledgeBaseIds", scope));
        }
        StringBuilder content = new StringBuilder("知识库检索结果（")
                .append(result.strategy().name()).append("）：\n");
        int rendered = 0;
        for (RetrievedChunk chunk : result.chunks()) {
            Integer index = context.evidence().add(chunk);
            if (index == null) {
                continue;
            }
            content.append('\n').append(context.evidence().render(index, chunk, properties.maxChunkChars()))
                    .append('\n');
            rendered++;
        }
        if (rendered == 0) {
            return ToolResult.success("知识库证据已达到本次请求上限，未加入新的证据。", Map.of(
                    "count", 0, "strategy", result.strategy().name(), "knowledgeBaseIds", scope));
        }
        return ToolResult.success(content.toString(), Map.of(
                "count", rendered, "strategy", result.strategy().name(), "knowledgeBaseIds", scope));
    }
}
