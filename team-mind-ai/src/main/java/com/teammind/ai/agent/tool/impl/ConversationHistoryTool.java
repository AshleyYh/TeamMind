package com.teammind.ai.agent.tool.impl;

import com.teammind.ai.agent.config.AgentProperties;
import com.teammind.ai.agent.model.ConversationMessage;
import com.teammind.ai.agent.tool.AgentTool;
import com.teammind.ai.agent.tool.ToolArguments;
import com.teammind.ai.agent.tool.ToolExecutionContext;
import com.teammind.ai.agent.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本次请求携带的会话历史查询。Phase 4 不持久化会话，只使用请求中的近期消息；
 * Phase 5 接入 Redis 后可替换数据来源而不改变工具协议。
 */
@Component
public class ConversationHistoryTool implements AgentTool {
    private final AgentProperties properties;

    public ConversationHistoryTool(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "getConversationHistory";
    }

    @Override
    public String description() {
        return "获取本次请求携带的最近对话消息，用于理解代词、省略和连续追问。最多返回 20 条。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "limit", Map.of("type", "integer", "description", "返回的消息条数",
                                "minimum", 1, "maximum", 20)),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> rawArguments) {
        ToolArguments arguments = ToolArguments.of(rawArguments);
        arguments.rejectUnknown("limit");
        Integer requested = arguments.optionalInt("limit", 1, 20);
        int limit = Math.min(requested == null ? 10 : requested, properties.maxHistoryMessages());
        List<ConversationMessage> history = context.history().stream()
                .filter(message -> "user".equals(message.role()) || "assistant".equals(message.role()))
                .toList();
        if (history.isEmpty()) {
            return ToolResult.success("当前请求没有携带历史对话。", Map.of("count", 0));
        }
        int start = Math.max(0, history.size() - limit);
        List<String> lines = new ArrayList<>();
        for (ConversationMessage message : history.subList(start, history.size())) {
            lines.add(("assistant".equals(message.role()) ? "助手：" : "用户：")
                    + abbreviate(message.content(), 500));
        }
        String content = "最近对话：\n" + String.join("\n", lines);
        return ToolResult.success(abbreviate(content, properties.maxObservationChars()),
                Map.of("count", history.size() - start));
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
