package com.teammind.ai.agent.react;

import com.teammind.ai.agent.model.ModelUsage;
import com.teammind.ai.agent.task.TaskType;
import com.teammind.ai.agent.task.TaskUnderstanding;
import com.teammind.ai.api.Citation;

import java.util.List;

/** Agent 非流式响应，包含任务理解、执行步骤、引用、终止原因和 Token 用量。 */
public record AgentAnswer(
        String answer,
        List<Citation> citations,
        String traceId,
        TaskType taskType,
        TaskUnderstanding taskUnderstanding,
        List<AgentStep> steps,
        TerminationReason termination,
        List<String> warnings,
        ModelUsage usage,
        String conversationId) {

    public AgentAnswer {
        citations = citations == null ? List.of() : List.copyOf(citations);
        steps = steps == null ? List.of() : List.copyOf(steps);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        usage = usage == null ? ModelUsage.empty() : usage;
    }
}
