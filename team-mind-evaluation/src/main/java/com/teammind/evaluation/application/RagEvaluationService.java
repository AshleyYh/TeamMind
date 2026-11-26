package com.teammind.evaluation.application;

import com.teammind.ai.api.Citation;
import com.teammind.ai.rag.RagAnswerService;
import com.teammind.evaluation.dataset.DatasetLoader;
import com.teammind.evaluation.dataset.RagDataset;
import com.teammind.evaluation.domain.EvaluationRun;
import com.teammind.evaluation.metric.MetricCalculator;
import com.teammind.evaluation.report.EvaluationReport;
import com.teammind.evaluation.report.EvaluationReportRenderer;
import com.teammind.knowledge.api.RetrievalResult;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.api.SearchStrategy;
import com.teammind.knowledge.application.KnowledgeSearchService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * RAG 检索评测：同一数据集分别执行多种检索策略，计算 Recall@K、MRR、NDCG@K，
 * 可选地生成答案并计算引用精确率、引用召回率和答案关键词覆盖率。
 */
@Service
public class RagEvaluationService {
    private final DatasetLoader datasetLoader;
    private final EvaluationRunService runService;
    private final KnowledgeSearchService searchService;
    private final RagAnswerService ragAnswerService;
    private final EvaluationReportRenderer reportRenderer;

    public RagEvaluationService(DatasetLoader datasetLoader, EvaluationRunService runService,
            KnowledgeSearchService searchService, RagAnswerService ragAnswerService,
            EvaluationReportRenderer reportRenderer) {
        this.datasetLoader = datasetLoader;
        this.runService = runService;
        this.searchService = searchService;
        this.ragAnswerService = ragAnswerService;
        this.reportRenderer = reportRenderer;
    }

    public EvaluationReport evaluate(Long ownerId, List<Long> knowledgeBaseIds,
            List<SearchStrategy> requestedStrategies, Integer topK, String datasetName,
            boolean evaluateAnswers) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            throw new com.teammind.common.exception.BusinessException(
                    com.teammind.common.api.ErrorCode.VALIDATION_FAILED, "RAG 评测至少需要一个知识库");
        }
        int limit = topK == null ? 5 : Math.min(Math.max(topK, 1), 20);
        List<SearchStrategy> strategies = requestedStrategies == null || requestedStrategies.isEmpty()
                ? Arrays.asList(SearchStrategy.values()) : requestedStrategies.stream().distinct().toList();
        DatasetLoader.DatasetResource<RagDataset> resource = datasetLoader.loadRag(datasetName);
        EvaluationRun run = runService.start("RAG", resource.name(), resource.hash(), ownerId,
                knowledgeBaseIds, strategies.stream().map(Enum::name).toList(),
                resource.dataset().caseCount() * strategies.size());
        try {
            List<EvaluationReport.CaseResult> caseResults = new ArrayList<>();
            Map<String, Object> strategyMetrics = new LinkedHashMap<>();
            for (SearchStrategy strategy : strategies) {
                List<MetricCalculator.RetrievalMetrics> retrievalMetrics = new ArrayList<>();
                List<Double> citationPrecisions = new ArrayList<>();
                List<Double> citationRecalls = new ArrayList<>();
                List<Double> keywordCoverages = new ArrayList<>();
                for (RagDataset.RagCase item : resource.dataset().cases()) {
                    long startedAt = System.nanoTime();
                    String caseId = strategy.name() + ":" + item.id();
                    try {
                        RetrievalResult result = searchService.searchWithTrace(item.query(), knowledgeBaseIds,
                                ownerId, limit, strategy, UUID.randomUUID().toString().replace("-", ""));
                        Map<String, List<RetrievedChunk>> byDocument = result.chunks().stream()
                                .collect(Collectors.groupingBy(chunk -> chunk.documentName() == null
                                        ? "" : chunk.documentName(), LinkedHashMap::new, Collectors.toList()));
                        List<String> documents = new ArrayList<>(byDocument.keySet());
                        Set<String> relevantNames = new HashSet<>(item.relevantDocumentNames());
                        List<Boolean> relevance = documents.stream()
                                .map(document -> isRelevant(document, byDocument.get(document), relevantNames,
                                        item.relevantKeywords()))
                                .toList();
                        int relevantCount = relevantNames.isEmpty()
                                ? (int) relevance.stream().filter(Boolean::booleanValue).count()
                                : relevantNames.size();
                        MetricCalculator.RetrievalMetrics metrics = MetricCalculator.score(relevance,
                                relevantCount);
                        retrievalMetrics.add(metrics);

                        double citationPrecision = 0;
                        double citationRecall = 0;
                        double keywordCoverage = 0;
                        String answerExcerpt = null;
                        String answerError = null;
                        boolean success = true;
                        int citationCount = 0;
                        if (evaluateAnswers) {
                            try {
                                RagAnswerService.RagAnswer answer = ragAnswerService.answer(item.query(),
                                        knowledgeBaseIds, ownerId, limit, strategy);
                                answerExcerpt = abbreviate(answer.answer(), 500);
                                Set<Integer> citedIndexes = citationIndexes(answer.answer());
                                citationCount = citedIndexes.size();
                                Set<String> citationDocuments = citedIndexes.stream()
                                        .filter(index -> index >= 1 && index <= answer.citations().size())
                                        .map(index -> answer.citations().get(index - 1).documentName())
                                        .filter(name -> name != null && !name.isBlank())
                                        .collect(Collectors.toCollection(LinkedHashSet::new));
                                long citedRelevant = citationDocuments.stream()
                                        .filter(relevantNames::contains).count();
                                citationPrecision = citationDocuments.isEmpty() ? 0
                                        : citedRelevant / (double) citationDocuments.size();
                                citationRecall = relevantNames.isEmpty() ? 0
                                        : citedRelevant / (double) relevantNames.size();
                                keywordCoverage = keywordCoverage(item.answerKeywords(), answer.answer());
                            } catch (RuntimeException exception) {
                                success = false;
                                answerError = exception.getMessage();
                            }
                        }
                        citationPrecisions.add(citationPrecision);
                        citationRecalls.add(citationRecall);
                        keywordCoverages.add(keywordCoverage);

                        Map<String, Object> expected = new LinkedHashMap<>();
                        expected.put("relevantDocumentNames", item.relevantDocumentNames());
                        expected.put("relevantKeywords", item.relevantKeywords());
                        Map<String, Object> actual = new LinkedHashMap<>();
                        actual.put("strategy", strategy.name());
                        actual.put("documents", documents);
                        actual.put("chunkCount", result.chunks().size());
                        actual.put("answer", answerExcerpt);
                        actual.put("citationCount", citationCount);
                        Map<String, Object> caseMetrics = new LinkedHashMap<>();
                        caseMetrics.put("recallAtK", metrics.recallAtK());
                        caseMetrics.put("mrr", metrics.mrr());
                        caseMetrics.put("ndcgAtK", metrics.ndcgAtK());
                        caseMetrics.put("citationPrecision", citationPrecision);
                        caseMetrics.put("citationRecall", citationRecall);
                        caseMetrics.put("keywordCoverage", keywordCoverage);
                        long duration = elapsedMillis(startedAt);
                        runService.recordCase(run.getRunId(), "RAG", caseId, item.query(), expected,
                                actual, caseMetrics, success, duration, answerError);
                        caseResults.add(new EvaluationReport.CaseResult(caseId, item.query(), success,
                                expected, actual, caseMetrics, answerError, duration));
                    } catch (RuntimeException exception) {
                        retrievalMetrics.add(MetricCalculator.score(List.of(), 0));
                        citationPrecisions.add(0.0);
                        citationRecalls.add(0.0);
                        keywordCoverages.add(0.0);
                        long duration = elapsedMillis(startedAt);
                        Map<String, Object> caseMetrics = Map.of("recallAtK", 0, "mrr", 0, "ndcgAtK", 0);
                        runService.recordCase(run.getRunId(), "RAG", caseId, item.query(),
                                Map.of("query", item.query()), Map.of("strategy", strategy.name()),
                                caseMetrics, false, duration, exception.getMessage());
                        caseResults.add(new EvaluationReport.CaseResult(caseId, item.query(), false,
                                Map.of("query", item.query()), Map.of("strategy", strategy.name()),
                                caseMetrics, exception.getMessage(), duration));
                    }
                }
                MetricCalculator.RetrievalMetrics average = MetricCalculator.average(retrievalMetrics);
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("recallAtK", average.recallAtK());
                metrics.put("mrr", average.mrr());
                metrics.put("ndcgAtK", average.ndcgAtK());
                metrics.put("citationPrecision", MetricCalculator.averageDouble(citationPrecisions));
                metrics.put("citationRecall", MetricCalculator.averageDouble(citationRecalls));
                metrics.put("keywordCoverage", MetricCalculator.averageDouble(keywordCoverages));
                strategyMetrics.put(strategy.name(), metrics);
            }
            Map<String, Object> overall = new LinkedHashMap<>();
            overall.put("topK", limit);
            overall.put("caseCount", run.getCaseCount());
            overall.put("strategies", strategyMetrics);
            run.setStatus("SUCCESS");
            run.setFinishedAt(LocalDateTime.now());
            String report = reportRenderer.render("RAG 检索评测报告", run, overall, caseResults);
            runService.finishSuccess(run.getRunId(), overall, report);
            return new EvaluationReport(run.getRunId(), "RAG", resource.name(), resource.hash(),
                    "SUCCESS", run.getCaseCount(), overall, caseResults, report,
                    run.getStartedAt(), run.getFinishedAt());
        } catch (RuntimeException exception) {
            runService.finishFailure(run.getRunId(), exception.getMessage());
            throw exception;
        }
    }

    private Set<Integer> citationIndexes(String answer) {
        Set<Integer> indexes = new LinkedHashSet<>();
        if (answer == null) {
            return indexes;
        }
        var matcher = java.util.regex.Pattern.compile("\\[(\\d+)]").matcher(answer);
        while (matcher.find()) {
            try {
                indexes.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // 超长编号不是合法引用，忽略。
            }
        }
        return indexes;
    }

    private boolean isRelevant(String document, List<RetrievedChunk> chunks, Set<String> relevantNames,
            List<String> relevantKeywords) {
        if (relevantNames.contains(document)) {
            return true;
        }
        if (relevantKeywords.isEmpty()) {
            return false;
        }
        return chunks.stream().anyMatch(chunk -> containsAny(chunk.content(), relevantKeywords));
    }

    private boolean containsAny(String content, List<String> keywords) {
        if (content == null) {
            return false;
        }
        return keywords.stream().anyMatch(keyword -> keyword != null && !keyword.isBlank()
                && content.contains(keyword));
    }

    private double keywordCoverage(List<String> keywords, String answer) {
        if (keywords == null || keywords.isEmpty() || answer == null) {
            return 0;
        }
        long hit = keywords.stream().filter(keyword -> keyword != null && answer.contains(keyword)).count();
        return hit / (double) keywords.size();
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
