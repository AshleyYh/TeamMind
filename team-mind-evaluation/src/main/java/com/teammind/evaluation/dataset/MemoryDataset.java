package com.teammind.evaluation.dataset;

import java.util.List;

/** 长期记忆检索数据集：每个用例给出一个期望命中的内容关键词。 */
public record MemoryDataset(String version, List<MemoryCase> cases) implements EvaluationDataset {

    public MemoryDataset {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    @Override
    public int caseCount() {
        return cases.size();
    }

    public record MemoryCase(String id, String query, String expectedKeyword) {
    }
}
