package com.teammind.evaluation.application;

import com.teammind.ai.agent.task.TaskContext;
import com.teammind.ai.agent.task.TaskUnderstanding;
import com.teammind.ai.agent.task.TaskUnderstandingService;
import com.teammind.evaluation.dataset.DatasetLoader;
import com.teammind.evaluation.dataset.TaskDataset;
import com.teammind.evaluation.domain.EvaluationRun;
import com.teammind.evaluation.report.EvaluationReport;
import com.teammind.evaluation.report.EvaluationReportRenderer;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 任务识别评测：比较任务类型和期望工具，输出准确率和逐用例结果。 */
@Service
public class TaskEvaluationService {
    private final DatasetLoader datasetLoader;
    private final EvaluationRunService runService;
    private final TaskUnderstandingService understandingService;
    private final EvaluationReportRenderer reportRenderer;

    public TaskEvaluationService(DatasetLoader datasetLoader, EvaluationRunService runService,
            TaskUnderstandingService understandingService, EvaluationReportRenderer reportRenderer) {
        this.datasetLoader = datasetLoader;
        this.runService = runService;
        this.understandingService = understandingService;
        this.reportRenderer = reportRenderer;
    }

    public EvaluationReport evaluate(Long ownerId, String datasetName, List<Long> knowledgeBaseIds) {
        DatasetLoader.DatasetResource<TaskDataset> resource = datasetLoader.loadTask(datasetName);
        EvaluationRun run = runService.start("TASK", resource.name(), resource.hash(), ownerId,
                knowledgeBaseIds, List.of(), resource.dataset().caseCount());
        try {
            List<EvaluationReport.CaseResult> cases = new ArrayList<>();
            int correct = 0;
            int toolTotal = 0;
            int toolCorrect = 0;
            for (TaskDataset.TaskCase item : resource.dataset().cases()) {
                long startedAt = System.nanoTime();
                try {
                    List<Long> scope = knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()
                            ? item.knowledgeBaseIds() : knowledgeBaseIds;
                    TaskContext context = new TaskContext(item.question(), ownerId, scope, List.of(),
                            List.of(), null);
                    TaskUnderstanding task = understandingService.understand(context);
                    boolean typeCorrect = task.taskType() == item.expectedTaskType();
                    boolean toolsCorrect = item.expectedTools().isEmpty()
                            || task.requiredTools().containsAll(item.expectedTools());
                    if (!item.expectedTools().isEmpty()) {
                        toolTotal++;
                        if (toolsCorrect) {
                            toolCorrect++;
                        }
                    }
                    if (typeCorrect) {
                        correct++;
                    }
                    boolean success = typeCorrect && toolsCorrect;
                    Map<String, Object> expected = new LinkedHashMap<>();
                    expected.put("taskType", item.expectedTaskType());
                    expected.put("tools", item.expectedTools());
                    Map<String, Object> actual = new LinkedHashMap<>();
                    actual.put("taskType", task.taskType());
                    actual.put("tools", task.requiredTools());
                    actual.put("confidence", task.confidence());
                    actual.put("ruleBased", task.ruleBased());
                    Map<String, Object> metrics = new LinkedHashMap<>();
                    metrics.put("typeCorrect", typeCorrect);
                    metrics.put("toolsCorrect", toolsCorrect);
                    long duration = elapsedMillis(startedAt);
                    runService.recordCase(run.getRunId(), "TASK", item.id(), item.question(),
                            expected, actual, metrics, success, duration, null);
                    cases.add(new EvaluationReport.CaseResult(item.id(), item.question(), success,
                            expected, actual, metrics, null, duration));
                } catch (RuntimeException exception) {
                    long duration = elapsedMillis(startedAt);
                    Map<String, Object> expected = Map.of("taskType", item.expectedTaskType());
                    Map<String, Object> actual = Map.of();
                    Map<String, Object> metrics = Map.of("typeCorrect", false, "toolsCorrect", false);
                    runService.recordCase(run.getRunId(), "TASK", item.id(), item.question(),
                            expected, actual, metrics, false, duration, exception.getMessage());
                    cases.add(new EvaluationReport.CaseResult(item.id(), item.question(), false,
                            expected, actual, metrics, exception.getMessage(), duration));
                }
            }
            int total = resource.dataset().caseCount();
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("accuracy", total == 0 ? 0 : correct / (double) total);
            metrics.put("correct", correct);
            metrics.put("total", total);
            metrics.put("toolSelectionAccuracy", toolTotal == 0 ? 0 : toolCorrect / (double) toolTotal);
            metrics.put("toolCases", toolTotal);
            run.setStatus("SUCCESS");
            run.setFinishedAt(LocalDateTime.now());
            String report = reportRenderer.render("任务识别评测报告", run, metrics, cases);
            runService.finishSuccess(run.getRunId(), metrics, report);
            return new EvaluationReport(run.getRunId(), "TASK", resource.name(), resource.hash(),
                    "SUCCESS", total, metrics, cases, report, run.getStartedAt(), run.getFinishedAt());
        } catch (RuntimeException exception) {
            runService.finishFailure(run.getRunId(), exception.getMessage());
            throw exception;
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
