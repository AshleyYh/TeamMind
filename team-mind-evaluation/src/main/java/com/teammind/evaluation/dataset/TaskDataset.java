package com.teammind.evaluation.dataset;

import com.teammind.ai.agent.task.TaskType;

import java.util.List;

/** 任务识别数据集：期望任务类型和可选期望工具。 */
public record TaskDataset(String version, List<TaskCase> cases) implements EvaluationDataset {

    public TaskDataset {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    @Override
    public int caseCount() {
        return cases.size();
    }

    public record TaskCase(String id, String question, TaskType expectedTaskType,
            List<String> expectedTools, List<Long> knowledgeBaseIds) {
        public TaskCase {
            expectedTools = expectedTools == null ? List.of() : List.copyOf(expectedTools);
            knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        }
    }
}
