package com.teammind.evaluation.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teammind.evaluation.domain.EvaluationRun;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 生成可直接保存或展示的 Markdown 评测报告。 */
@Component
public class EvaluationReportRenderer {
    private final ObjectMapper objectMapper;

    public EvaluationReportRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String render(String title, EvaluationRun run, Map<String, Object> metrics,
            List<EvaluationReport.CaseResult> cases) {
        StringBuilder report = new StringBuilder();
        report.append("# ").append(title).append("\n\n");
        report.append("- Run ID：").append(run.getRunId()).append('\n');
        report.append("- 数据集：").append(run.getDatasetName()).append('\n');
        report.append("- 数据集 Hash：").append(run.getDatasetHash()).append('\n');
        report.append("- 状态：").append(run.getStatus()).append('\n');
        report.append("- 用例数：").append(run.getCaseCount()).append('\n');
        report.append("- 类型：").append(run.getDatasetType()).append('\n');
        if (run.getKnowledgeBaseIds() != null && !run.getKnowledgeBaseIds().isBlank()) {
            report.append("- 知识库：").append(run.getKnowledgeBaseIds()).append('\n');
        }
        if (run.getStrategies() != null && !run.getStrategies().isBlank()) {
            report.append("- 检索策略：").append(run.getStrategies()).append('\n');
        }
        report.append("- 开始时间：").append(run.getStartedAt()).append('\n');
        report.append("- 结束时间：").append(run.getFinishedAt()).append('\n');

        report.append("\n## 汇总指标\n\n```json\n");
        try {
            report.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metrics));
        } catch (JsonProcessingException exception) {
            report.append(metrics);
        }
        report.append("\n```\n\n## 用例结果\n\n");
        report.append("| 用例 | 成功 | 耗时(ms) | 指标 | 错误 |\n");
        report.append("| --- | --- | ---: | --- | --- |\n");
        for (EvaluationReport.CaseResult item : cases) {
            report.append("| ").append(escape(item.caseId()))
                    .append(" | ").append(item.success() ? "是" : "否")
                    .append(" | ").append(item.durationMillis())
                    .append(" | ").append(escape(toJson(item.metrics())))
                    .append(" | ").append(escape(item.errorMessage() == null ? "" : item.errorMessage()))
                    .append(" |\n");
        }
        return report.toString();
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }
}
