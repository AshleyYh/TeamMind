package com.teammind.ai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTests {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 工具名称重复时启动失败() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(tool("search"), tool("search")), objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void 未知工具返回失败观察而不是抛异常() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("search")), objectMapper);

        ToolResult result = registry.execute("shell", "{}", null);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("未知工具");
        assertThat(result.metadata()).containsEntry("error", "UNKNOWN_TOOL");
    }

    @Test
    void 非法JSON参数返回失败观察() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("search")), objectMapper);

        ToolResult result = registry.execute("search", "不是JSON", null);

        assertThat(result.success()).isFalse();
        assertThat(result.metadata()).containsEntry("error", "INVALID_ARGUMENTS");
    }

    @Test
    void 工具异常被转换为稳定失败文案() {
        AgentTool broken = new AgentTool() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public String description() {
                return "故意失败的测试工具";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
                throw new IllegalStateException("内部数据库地址 jdbc:secret");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(broken), objectMapper);

        ToolResult result = registry.execute("broken", "{}", null);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).doesNotContain("jdbc:secret");
        assertThat(result.content()).contains("工具执行失败");
    }

    @Test
    void 工具参数白名单拒绝未知字段() {
        ToolArguments arguments = ToolArguments.of(Map.of("query", "连接池", "danger", "drop table"));

        assertThatThrownBy(() -> arguments.rejectUnknown("query", "topK"))
                .isInstanceOf(ToolArguments.ToolArgumentException.class)
                .hasMessageContaining("未知字段");
    }

    private AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "测试工具";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
                return ToolResult.success("ok");
            }
        };
    }
}
