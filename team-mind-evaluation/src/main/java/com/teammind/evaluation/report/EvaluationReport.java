package com.teammind.evaluation.report;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 评测报告：聚合指标、逐用例结果和 Markdown 文本，运行记录同时持久化到数据库。 */
public record EvaluationReport(
        String runId,
        String datasetType,
        String datasetName,
        String datasetHash,
        String status,
        int caseCount,
        Map<String, Object> metrics,
        List<CaseResult> cases,
        String reportMarkdown,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {

    public EvaluationReport {
        // 评测实际结果可能包含 null（例如未启用答案评测时的 answer），因此使用允许 null 值的只读 Map。
        metrics = metrics == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record CaseResult(
            String caseId,
            String query,
            boolean success,
            Map<String, Object> expected,
            Map<String, Object> actual,
            Map<String, Object> metrics,
            String errorMessage,
            long durationMillis) {

        public CaseResult {
            expected = expected == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(expected));
            actual = actual == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(actual));
            metrics = metrics == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        }
    }
}
