package com.teammind.evaluation.dataset;

import java.util.List;

/** RAG 检索数据集：相关文档名和可选答案关键词。 */
public record RagDataset(String version, List<RagCase> cases) implements EvaluationDataset {

    public RagDataset {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    @Override
    public int caseCount() {
        return cases.size();
    }

    public record RagCase(String id, String query, List<String> relevantDocumentNames,
            List<String> relevantKeywords, List<String> answerKeywords) {
        public RagCase {
            relevantDocumentNames = relevantDocumentNames == null ? List.of() : List.copyOf(relevantDocumentNames);
            relevantKeywords = relevantKeywords == null ? List.of() : List.copyOf(relevantKeywords);
            answerKeywords = answerKeywords == null ? List.of() : List.copyOf(answerKeywords);
        }
    }
}
