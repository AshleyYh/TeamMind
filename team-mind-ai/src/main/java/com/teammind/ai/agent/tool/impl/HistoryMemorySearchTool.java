package com.teammind.ai.agent.tool.impl;

import com.teammind.ai.agent.tool.AgentTool;
import com.teammind.ai.agent.tool.ToolArguments;
import com.teammind.ai.agent.tool.ToolExecutionContext;
import com.teammind.ai.agent.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 历史经验查询工具占位实现。Phase 4 尚未实现长期记忆，工具必须明确返回不可用，
 * 不能伪造历史经验，也不能让模型误以为已经检索过记忆。
 */
@Component
public class HistoryMemorySearchTool implements AgentTool {

    @Override
    public String name() {
        return "historyMemorySearch";
    }

    @Override
    public String description() {
        return "检索过去会话中确认过的长期经验。Phase 5 前不可用，调用后会返回明确的不可用说明。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "历史经验检索问题", "maxLength", 1000),
                        "topK", Map.of("type", "integer", "description", "返回条数",
                                "minimum", 1, "maximum", 10)),
                "required", List.of("query"),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> rawArguments) {
        ToolArguments arguments = ToolArguments.of(rawArguments);
        arguments.rejectUnknown("query", "topK");
        arguments.requireString("query", 1000);
        return ToolResult.failure(
                "长期记忆检索尚未启用（Phase 5），当前无法查询历史经验。请改用知识库检索，或明确告知用户该限制。",
                Map.of("error", "MEMORY_NOT_AVAILABLE", "available", false));
    }
}
