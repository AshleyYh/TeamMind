package com.teammind.ai.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teammind.ai.agent.model.AgentToolSpec;
import com.teammind.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具注册表：启动时校验工具名唯一，运行时只允许调用已注册工具。
 * 未知工具、非法 JSON、参数错误和工具异常都转换为失败 Observation，不让模型异常穿透到客户端。
 */
@Component
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final Pattern TOOL_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{1,49}");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;

    public ToolRegistry(List<AgentTool> toolBeans, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        for (AgentTool tool : toolBeans) {
            String name = tool.name() == null ? "" : tool.name().trim();
            if (!TOOL_NAME.matcher(name).matches()) {
                throw new IllegalStateException("工具名称不合法：" + name);
            }
            if (tool.description() == null || tool.description().isBlank()) {
                throw new IllegalStateException("工具必须提供描述：" + name);
            }
            if (tool.parameters() == null || !"object".equals(tool.parameters().get("type"))) {
                throw new IllegalStateException("工具参数必须是 JSON Schema 对象：" + name);
            }
            if (tools.putIfAbsent(name, tool) != null) {
                throw new IllegalStateException("工具名称重复：" + name);
            }
        }
    }

    public List<AgentToolSpec> specifications() {
        return tools.values().stream()
                .map(tool -> new AgentToolSpec(tool.name(), tool.description(), tool.parameters()))
                .toList();
    }

    public Set<String> names() {
        return Set.copyOf(tools.keySet());
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public ToolResult execute(String name, String argumentsJson, ToolExecutionContext context) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            return ToolResult.failure("未知工具：" + name + "。只能调用已注册的工具。",
                    Map.of("error", "UNKNOWN_TOOL"));
        }
        Map<String, Object> arguments;
        try {
            String json = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                return ToolResult.failure("工具参数必须是 JSON 对象。", Map.of("error", "INVALID_ARGUMENTS"));
            }
            arguments = objectMapper.convertValue(node, MAP_TYPE);
        } catch (Exception exception) {
            return ToolResult.failure("工具参数不是合法 JSON。", Map.of("error", "INVALID_ARGUMENTS"));
        }
        try {
            ToolResult result = tool.execute(context, arguments);
            return result == null ? ToolResult.failure("工具没有返回结果。") : result;
        } catch (ToolArguments.ToolArgumentException exception) {
            return ToolResult.failure(exception.getMessage(), Map.of("error", "INVALID_ARGUMENTS"));
        } catch (BusinessException exception) {
            // 业务异常信息是面向用户的稳定文案，可作为 Observation 让模型调整策略。
            return ToolResult.failure(exception.getMessage(), Map.of("error", "BUSINESS_FAILED"));
        } catch (RuntimeException exception) {
            // 未预期异常只记录日志，不把堆栈、SQL 或内部地址返回给模型。
            log.warn("工具执行失败：name={}", name, exception);
            return ToolResult.failure("工具执行失败，请调整参数或稍后重试。", Map.of("error", "TOOL_FAILED"));
        }
    }
}
