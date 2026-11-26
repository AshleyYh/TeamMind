package com.teammind.knowledge.application;

import com.teammind.knowledge.api.EvidenceRecorder;
import com.teammind.knowledge.api.RerankerGateway;
import com.teammind.knowledge.api.RetrievalTraceRecorder;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.api.SearchStrategy;
import com.teammind.knowledge.config.QdrantKnowledgeProperties;
import com.teammind.knowledge.config.RetrievalProperties;
import com.teammind.knowledge.infrastructure.search.ReciprocalRankFusion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeSearchServiceTests {
    private final DenseRetrievalService dense = mock(DenseRetrievalService.class);
    private final Bm25RetrievalService bm25 = mock(Bm25RetrievalService.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final RerankerGateway reranker = mock(RerankerGateway.class);
    private final EvidenceRecorder recorder = mock(EvidenceRecorder.class);
    private final RetrievalTraceRecorder traceRecorder = mock(RetrievalTraceRecorder.class);
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final KnowledgeSearchService service = new KnowledgeSearchService(dense, bm25, knowledgeBaseService,
            new QdrantKnowledgeProperties("test", 2, 5, 0.0),
            new RetrievalProperties(20, 20, 20, SearchStrategy.HYBRID_RERANK, 60, 64, Duration.ofSeconds(2)),
            new ReciprocalRankFusion(), reranker, recorder, traceRecorder, executor);

    @Test
    void 混合重排保留名次且限制最终数量() {
        when(dense.retrieve(anyString(), anyList(), any(), anyInt(), anyDouble())).thenReturn(List.of(chunk(1)));
        when(bm25.retrieve(anyString(), anyList(), any(), anyInt())).thenReturn(List.of(chunk(2)));
        when(reranker.rerank(anyString(), anyList())).thenReturn(List.of(
                new RerankerGateway.RerankResult(1, 0.9), new RerankerGateway.RerankResult(0, 0.8)));

        var result = service.searchWithTrace("问题", List.of(1L), 1L, 1, null);

        assertThat(result.traceId()).hasSize(32);
        assertThat(result.chunks()).singleElement().satisfies(chunk -> {
            assertThat(chunk.chunkId()).isEqualTo(2L);
            assertThat(chunk.bm25Rank()).isEqualTo(1);
            assertThat(chunk.rerankScore()).isEqualTo(0.9);
        });
        verify(recorder).record(anyString(), any(), anyString(), anyList(), any(), anyList());
        verify(traceRecorder).record(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.isNull());
        executor.shutdownNow();
    }

    @Test
    void 纯BM25不会调用向量或重排模型() {
        when(bm25.retrieve(anyString(), anyList(), any(), anyInt())).thenReturn(List.of(chunk(2)));
        var result = service.searchWithTrace("问题", List.of(1L), 1L, 5, SearchStrategy.BM25);
        assertThat(result.strategy()).isEqualTo(SearchStrategy.BM25);
        assertThat(result.chunks()).hasSize(1);
        verifyNoInteractions(dense, reranker);
        executor.shutdownNow();
    }

    private RetrievedChunk chunk(long id) {
        return new RetrievedChunk(id, 1L, 1L, "研发.md", 1, 0, null, "内容" + id, null, null, null, null, null, 0d, null);
    }
}
