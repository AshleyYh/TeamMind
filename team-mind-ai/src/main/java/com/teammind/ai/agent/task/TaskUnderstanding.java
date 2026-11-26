package com.teammind.ai.agent.task;

import com.teammind.ai.agent.model.ModelUsage;

import java.util.List;

/** LLM 或规则层输出的结构化任务理解结果，所有字段都已经过服务端校验。 */
public record TaskUnderstanding(
        TaskType taskType,
        String userIntent,
        List<String> entities,
        List<String> keywords,
        List<Long> knowledgeBaseIds,
        TimeRange timeRange,
        List<String> requiredTools,
        double confidence,
        boolean ruleBased,
        List<String> warnings,
        ModelUsage usage) {

    public TaskUnderstanding {
        entities = entities == null ? List.of() : List.copyOf(entities);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        usage = usage == null ? ModelUsage.empty() : usage;
    }

    /** 兼容规则任务和测试中的无用量构造。 */
    public TaskUnderstanding(TaskType taskType, String userIntent, List<String> entities,
            List<String> keywords, List<Long> knowledgeBaseIds, TimeRange timeRange,
            List<String> requiredTools, double confidence, boolean ruleBased,
            List<String> warnings) {
        this(taskType, userIntent, entities, keywords, knowledgeBaseIds, timeRange,
                requiredTools, confidence, ruleBased, warnings, ModelUsage.empty());
    }
}
