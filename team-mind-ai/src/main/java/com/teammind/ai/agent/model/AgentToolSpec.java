package com.teammind.ai.agent.model;

import java.util.Map;

/** 暴露给模型的工具描述，参数使用 JSON Schema 表达。 */
public record AgentToolSpec(String name, String description, Map<String, Object> parameters) {
}
