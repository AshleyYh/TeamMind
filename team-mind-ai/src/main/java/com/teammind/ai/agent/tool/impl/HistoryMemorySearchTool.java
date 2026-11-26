package com.teammind.ai.agent.tool.impl;

import com.teammind.ai.agent.config.AgentProperties;
import com.teammind.ai.agent.tool.AgentTool;
import com.teammind.ai.agent.tool.ToolArguments;
import com.teammind.ai.agent.tool.ToolExecutionContext;
import com.teammind.ai.agent.tool.ToolResult;
import com.teammind.memory.application.MemoryService;
import com.teammind.memory.domain.Memory;
import com.teammind.memory.domain.MemoryType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 长期记忆检索工具。只返回用户已确认的 ACTIVE 记忆，
 * 不返回草稿、已失效或其他用户的数据；长期记忆编号不是文档引用编号。
 */
@Component
public class HistoryMemorySearchTool implements AgentTool {
    private final MemoryService memoryService;
    private final AgentProperties properties;

    public HistoryMemorySearchTool(MemoryService memoryService, AgentProperties properties) {
        this.memoryService = memoryService;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "historyMemorySearch";
    }

    @Override
    public String description() {
        return "检索用户已确认的长期记忆，例如稳定事实、偏好、决策和历史经验。只返回 ACTIVE 记忆。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "记忆检索问题", "maxLength", 1000),
                        "topK", Map.of("type", "integer", "description", "返回条数",
                                "minimum", 1, "maximum", 10),
                        "memoryType", Map.of("type", "string", "description", "可选记忆类型",
                                "enum", List.of("FACT", "PREFERENCE", "DECISION", "EXPERIENCE",
                                        "PROFILE", "OTHER"))),
                "required", List.of("query"),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> rawArguments) {
        ToolArguments arguments = ToolArguments.of(rawArguments);
        arguments.rejectUnknown("query", "topK", "memoryType");
        String query = arguments.requireString("query", 1000);
        Integer topK = arguments.optionalInt("topK", 1, 10);
        MemoryType memoryType = arguments.optionalEnum("memoryType", MemoryType.class);

        List<Memory> memories = memoryService.search(context.ownerId(), query,
                        topK == null ? 5 : topK)
                .stream()
                .filter(memory -> memoryType == null || memory.getMemoryType() == memoryType)
                .toList();
        if (memories.isEmpty()) {
            return ToolResult.success("没有找到相关的已确认长期记忆。", Map.of("count", 0));
        }
        StringBuilder content = new StringBuilder("已确认的长期记忆：\n");
        for (int index = 0; index < memories.size(); index++) {
            Memory memory = memories.get(index);
            content.append("\n记忆#").append(index + 1).append(" 类型：").append(memory.getMemoryType())
                    .append("\n标题：").append(memory.getTitle())
                    .append("\n内容：").append(memory.getContent())
                    .append("\n来源会话：").append(memory.getSourceConversationId() == null
                            ? "无" : memory.getSourceConversationId())
                    .append("\n更新时间：").append(memory.getUpdatedAt())
                    .append('\n');
        }
        return ToolResult.success(abbreviate(content.toString(), properties.maxObservationChars()),
                Map.of("count", memories.size(),
                        "memoryIds", memories.stream().map(Memory::getId).toList()));
    }

    private String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
