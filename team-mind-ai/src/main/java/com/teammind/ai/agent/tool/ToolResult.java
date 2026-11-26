package com.teammind.ai.agent.tool;

import java.util.Map;

/** 工具执行结果。失败也会作为 Observation 返回模型，以便模型调整策略或说明限制。 */
public record ToolResult(boolean success, String content, Map<String, Object> metadata) {

    public ToolResult {
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ToolResult success(String content) {
        return new ToolResult(true, content, Map.of());
    }

    public static ToolResult success(String content, Map<String, Object> metadata) {
        return new ToolResult(true, content, metadata);
    }

    public static ToolResult failure(String content) {
        return new ToolResult(false, content, Map.of("error", "TOOL_FAILED"));
    }

    public static ToolResult failure(String content, Map<String, Object> metadata) {
        return new ToolResult(false, content, metadata);
    }
}
