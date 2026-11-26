package com.teammind.ai.rag;

import com.teammind.ai.model.ChatCompletionGateway;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.application.KnowledgeSearchService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagAnswerServiceTests {

    private final KnowledgeSearchService searchService = mock(KnowledgeSearchService.class);
    private final ChatCompletionGateway chatGateway = mock(ChatCompletionGateway.class);
    private final com.teammind.knowledge.api.EvidenceRecorder evidenceRecorder =
            mock(com.teammind.knowledge.api.EvidenceRecorder.class);
    private final com.teammind.observability.application.RagTraceService traceService =
            mock(com.teammind.observability.application.RagTraceService.class);
    private final RagAnswerService service = new RagAnswerService(searchService, chatGateway, evidenceRecorder, traceService);

    {
        var trace = new com.teammind.observability.domain.RagTrace();
        trace.setId(99L);
        org.mockito.Mockito.when(traceService.start(anyString(), any(), anyString(), any(), any())).thenReturn(trace);
    }

    @Test
    void 无证据时不调用模型() {
        when(searchService.searchWithTrace(anyString(), any(), any(), any(), any(), anyString()))
                .thenReturn(new com.teammind.knowledge.api.RetrievalResult("trace",
                        com.teammind.knowledge.api.SearchStrategy.HYBRID, List.of()));

        var result = service.answer("连接池大小是多少？", List.of(1L), 1L, null);

        assertThat(result.answer()).contains("没有找到足够依据");
        assertThat(result.citations()).isEmpty();
        verifyNoInteractions(chatGateway);
    }

    @Test
    void 引用只来自真实检索结果() {
        RetrievedChunk chunk = new RetrievedChunk(11L, 22L, 1L, "订单设计.pdf", 2, 0, 3,
                "最大连接数为30", 0.9);
        when(searchService.searchWithTrace(anyString(), any(), any(), any(), any(), anyString()))
                .thenReturn(new com.teammind.knowledge.api.RetrievalResult("trace",
                        com.teammind.knowledge.api.SearchStrategy.HYBRID, List.of(chunk)));
        when(chatGateway.generateWithUsage(anyString(), anyString()))
                .thenReturn(new ChatCompletionGateway.ChatCompletionResult("最大连接数为30 [1]", 10, 5));

        var result = service.answer("最大连接数？", List.of(1L), 1L, 5);

        assertThat(result.answer()).contains("[1]");
        assertThat(result.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.index()).isEqualTo(1);
            assertThat(citation.chunkId()).isEqualTo(11L);
            assertThat(citation.documentId()).isEqualTo(22L);
            assertThat(citation.pageNumber()).isEqualTo(3);
        });
        verify(chatGateway).generateWithUsage(anyString(),
                org.mockito.ArgumentMatchers.contains("最大连接数为30"));
        verify(evidenceRecorder).markCitations(org.mockito.ArgumentMatchers.eq(result.traceId()),
                org.mockito.ArgumentMatchers.eq(java.util.Set.of(11L)));
        verify(traceService).finish(eq(99L), eq(true), anyLong(), eq("最大连接数为30 [1]"),
                eq(10), eq(5), isNull());
    }

    @Test
    void 拒绝模型返回不存在的引用编号() {
        RetrievedChunk chunk = new RetrievedChunk(11L, 22L, 1L, "订单设计.pdf", 2, 0, 3,
                "最大连接数为30", 0.9);
        when(searchService.searchWithTrace(anyString(), any(), any(), any(), any(), anyString()))
                .thenReturn(new com.teammind.knowledge.api.RetrievalResult("trace",
                        com.teammind.knowledge.api.SearchStrategy.HYBRID, List.of(chunk)));
        when(chatGateway.generateWithUsage(anyString(), anyString()))
                .thenReturn(new ChatCompletionGateway.ChatCompletionResult("错误引用 [2]", 10, 5));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.answer("最大连接数？", List.of(1L), 1L, 5))
                .isInstanceOf(com.teammind.common.exception.BusinessException.class)
                .hasMessageContaining("不存在的证据引用");
    }
}
