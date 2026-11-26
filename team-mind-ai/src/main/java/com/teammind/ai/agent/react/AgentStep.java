package com.teammind.ai.agent.react;

import java.util.Map;

/** 单步执行记录，内容经过截断，避免把完整文档和提示词暴露给前端或日志。 */
public record AgentStep(
        int index,
        AgentStepType type,
        String name,
        String content,
        Map<String, Object> arguments,
        boolean success,
        long durationMillis) {

    public AgentStep {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
