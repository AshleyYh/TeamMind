package com.teammind.ai.agent.tool;

import java.util.Map;

/**
 * Agent 工具端口。模型只能看到名称、描述和 JSON Schema，不能直接访问数据库或执行任意代码。
 */
public interface AgentTool {

    String name();

    String description();

    Map<String, Object> parameters();

    ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments);
}
