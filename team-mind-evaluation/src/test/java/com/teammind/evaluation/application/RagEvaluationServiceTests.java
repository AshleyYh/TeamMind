package com.teammind.evaluation.application;

import com.teammind.ai.api.Citation;
import com.teammind.ai.rag.RagAnswerService;
import com.teammind.evaluation.dataset.DatasetLoader;
import com.teammind.evaluation.dataset.RagDataset;
import com.teammind.evaluation.domain.EvaluationRun;
import com.teammind.evaluation.report.EvaluationReport;
import com.teammind.evaluation.report.EvaluationReportRenderer;
import com.teammind.knowledge.api.RetrievalResult;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.api.SearchStrategy;
import com.teammind.knowledge.application.KnowledgeSearchService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagEvaluationServiceTests {
    private final DatasetLoader loader = mock(DatasetLoader.class);
    private final EvaluationRunService runService = mock(EvaluationRunService.class);
    private final KnowledgeSearchService searchService = mock(KnowledgeSearchService.class);
    private final RagAnswerService ragAnswerService = mock(RagAnswerService.class);
    private final EvaluationReportRenderer renderer = mock(EvaluationReportRenderer.class);
    private final RagEvaluationService service = new RagEvaluationService(loader, runService,
            searchService, ragAnswerService, renderer);

    @Test
    void 检索评测计算召回和排名指标() {
        when(loader.loadRag("rag-retrieval")).thenReturn(
                new DatasetLoader.DatasetResource<>("rag-retrieval", "hash", dataset(), "RAG"));
        when(runService.start(anyString(), anyString(), anyString(), any(), anyList(), anyList(), anyInt()))
                .thenReturn(run());
        when(searchService.searchWithTrace(anyString(), anyList(), any(), anyInt(), any(), anyString()))
                .thenReturn(new RetrievalResult("trace", SearchStrategy.DENSE,
                        List.of(chunk(1L, "a.pdf"), chunk(2L, "b.pdf"))));
        when(renderer.render(anyString(), any(), any(), any())).thenReturn("report");

        EvaluationReport report = service.evaluate(1L, List.of(1L), List.of(SearchStrategy.DENSE),
                5, "rag-retrieval", false);

        assertThat(report.status()).isEqualTo("SUCCESS");
        @SuppressWarnings("unchecked")
        Map<String, Object> strategies = (Map<String, Object>) report.metrics().get("strategies");
        @SuppressWarnings("unchecked")
        Map<String, Object> dense = (Map<String, Object>) strategies.get("DENSE");
        assertThat(dense).containsEntry("recallAtK", 1.0);
        assertThat(dense).containsEntry("mrr", 1.0);
        verify(runService).recordCase(anyString(), eq("RAG"), eq("DENSE:r1"), anyString(),
                any(), any(), any(), eq(true), any(Long.class), eq(null));
    }

    @Test
    void 可选答案评测计算引用和关键词指标() {
        when(loader.loadRag("rag-retrieval")).thenReturn(
                new DatasetLoader.DatasetResource<>("rag-retrieval", "hash", dataset(), "RAG"));
        when(runService.start(anyString(), anyString(), anyString(), any(), anyList(), anyList(), anyInt()))
                .thenReturn(run());
        when(searchService.searchWithTrace(anyString(), anyList(), any(), anyInt(), any(), anyString()))
                .thenReturn(new RetrievalResult("trace", SearchStrategy.DENSE,
                        List.of(chunk(1L, "a.pdf"))));
        when(ragAnswerService.answer(anyString(), anyList(), any(), any(), any()))
                .thenReturn(new RagAnswerService.RagAnswer("最大连接数为 30 [1]",
                        List.of(citation("a.pdf")), "trace", SearchStrategy.DENSE));
        when(renderer.render(anyString(), any(), any(), any())).thenReturn("report");

        EvaluationReport report = service.evaluate(1L, List.of(1L), List.of(SearchStrategy.DENSE),
                5, "rag-retrieval", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> strategies = (Map<String, Object>) report.metrics().get("strategies");
        @SuppressWarnings("unchecked")
        Map<String, Object> dense = (Map<String, Object>) strategies.get("DENSE");
        assertThat(dense).containsEntry("citationPrecision", 1.0);
        assertThat(dense).containsEntry("citationRecall", 1.0);
        assertThat(dense).containsEntry("keywordCoverage", 1.0);
    }

    private RagDataset dataset() {
        return new RagDataset("1.0", List.of(new RagDataset.RagCase("r1", "连接数",
                List.of("a.pdf"), List.of("连接数"), List.of("30"))));
    }

    private RetrievedChunk chunk(Long id, String documentName) {
        return new RetrievedChunk(id, 1L, 1L, documentName, 1, 0, null,
                documentName.equals("a.pdf") ? "最大连接数为30" : "其他内容", 0.9);
    }

    private Citation citation(String documentName) {
        return new Citation(1, 1L, 1L, 1L, documentName, 1, 1, "最大连接数为30", 0.9,
                null, null, null, null, null, null);
    }

    private EvaluationRun run() {
        EvaluationRun run = new EvaluationRun();
        run.setRunId("0123456789abcdef0123456789abcdef");
        run.setStartedAt(LocalDateTime.now());
        run.setCaseCount(1);
        run.setStatus("RUNNING");
        return run;
    }
}
