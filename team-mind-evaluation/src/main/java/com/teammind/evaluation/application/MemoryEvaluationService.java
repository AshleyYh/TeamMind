package com.teammind.evaluation.application;

import com.teammind.evaluation.dataset.DatasetLoader;
import com.teammind.evaluation.dataset.MemoryDataset;
import com.teammind.evaluation.domain.EvaluationRun;
import com.teammind.evaluation.metric.MetricCalculator;
import com.teammind.evaluation.report.EvaluationReport;
import com.teammind.evaluation.report.EvaluationReportRenderer;
import com.teammind.memory.application.MemoryService;
import com.teammind.memory.domain.Memory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 长期记忆检索评测：用期望关键词判断返回记忆是否命中，计算 Recall@K、MRR、NDCG@K。 */
@Service
public class MemoryEvaluationService {
    private final DatasetLoader datasetLoader;
    private final EvaluationRunService runService;
    private final MemoryService memoryService;
    private final EvaluationReportRenderer reportRenderer;

    public MemoryEvaluationService(DatasetLoader datasetLoader, EvaluationRunService runService,
            MemoryService memoryService, EvaluationReportRenderer reportRenderer) {
        this.datasetLoader = datasetLoader;
        this.runService = runService;
        this.memoryService = memoryService;
        this.reportRenderer = reportRenderer;
    }

    public EvaluationReport evaluate(Long ownerId, Integer topK, String datasetName) {
        int limit = topK == null ? 5 : Math.min(Math.max(topK, 1), 20);
        DatasetLoader.DatasetResource<MemoryDataset> resource = datasetLoader.loadMemory(datasetName);
        EvaluationRun run = runService.start("MEMORY", resource.name(), resource.hash(), ownerId,
                List.of(), List.of(), resource.dataset().caseCount());
        try {
            List<EvaluationReport.CaseResult> cases = new ArrayList<>();
            List<MetricCalculator.RetrievalMetrics> metricsList = new ArrayList<>();
            for (MemoryDataset.MemoryCase item : resource.dataset().cases()) {
                long startedAt = System.nanoTime();
                try {
                    List<Memory> memories = memoryService.search(ownerId, item.query(), limit);
                    List<Boolean> relevance = memories.stream()
                            .map(memory -> contains(memory, item.expectedKeyword())).toList();
                    MetricCalculator.RetrievalMetrics metrics = MetricCalculator.score(relevance, 1);
                    metricsList.add(metrics);
                    Map<String, Object> expected = Map.of("keyword", item.expectedKeyword());
                    Map<String, Object> actual = Map.of("memories", memories.stream()
                            .map(Memory::getTitle).toList());
                    Map<String, Object> caseMetrics = new LinkedHashMap<>();
                    caseMetrics.put("recallAtK", metrics.recallAtK());
                    caseMetrics.put("mrr", metrics.mrr());
                    caseMetrics.put("ndcgAtK", metrics.ndcgAtK());
                    long duration = elapsedMillis(startedAt);
                    runService.recordCase(run.getRunId(), "MEMORY", item.id(), item.query(),
                            expected, actual, caseMetrics, true, duration, null);
                    cases.add(new EvaluationReport.CaseResult(item.id(), item.query(), true,
                            expected, actual, caseMetrics, null, duration));
                } catch (RuntimeException exception) {
                    metricsList.add(MetricCalculator.score(List.of(), 0));
                    long duration = elapsedMillis(startedAt);
                    runService.recordCase(run.getRunId(), "MEMORY", item.id(), item.query(),
                            Map.of("keyword", item.expectedKeyword()), Map.of(),
                            Map.of("recallAtK", 0, "mrr", 0, "ndcgAtK", 0), false, duration,
                            exception.getMessage());
                    cases.add(new EvaluationReport.CaseResult(item.id(), item.query(), false,
                            Map.of("keyword", item.expectedKeyword()), Map.of(),
                            Map.of("recallAtK", 0, "mrr", 0, "ndcgAtK", 0),
                            exception.getMessage(), duration));
                }
            }
            MetricCalculator.RetrievalMetrics average = MetricCalculator.average(metricsList);
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("topK", limit);
            metrics.put("caseCount", resource.dataset().caseCount());
            metrics.put("recallAtK", average.recallAtK());
            metrics.put("mrr", average.mrr());
            metrics.put("ndcgAtK", average.ndcgAtK());
            run.setStatus("SUCCESS");
            run.setFinishedAt(LocalDateTime.now());
            String report = reportRenderer.render("长期记忆检索评测报告", run, metrics, cases);
            runService.finishSuccess(run.getRunId(), metrics, report);
            return new EvaluationReport(run.getRunId(), "MEMORY", resource.name(), resource.hash(),
                    "SUCCESS", resource.dataset().caseCount(), metrics, cases, report,
                    run.getStartedAt(), run.getFinishedAt());
        } catch (RuntimeException exception) {
            runService.finishFailure(run.getRunId(), exception.getMessage());
            throw exception;
        }
    }

    private boolean contains(Memory memory, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return false;
        }
        return contains(memory.getTitle(), keyword) || contains(memory.getContent(), keyword)
                || contains(memory.getSummary(), keyword);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.contains(keyword);
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
