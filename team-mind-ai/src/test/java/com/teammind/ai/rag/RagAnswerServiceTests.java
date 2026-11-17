package com.teammind.ai.rag;

import com.teammind.ai.model.ChatCompletionGateway;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.application.KnowledgeSearchService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagAnswerServiceTests {

    private final KnowledgeSearchService searchService = mock(KnowledgeSearchService.class);
    private final ChatCompletionGateway chatGateway = mock(ChatCompletionGateway.class);
    private final RagAnswerService service = new RagAnswerService(searchService, chatGateway);

    @Test
    void 无证据时不调用模型() {
        when(searchService.search(anyString(), any(), any(), any())).thenReturn(List.of());

        var result = service.answer("连接池大小是多少？", List.of(1L), 1L, null);

        assertThat(result.answer()).contains("没有找到足够依据");
        assertThat(result.citations()).isEmpty();
        verifyNoInteractions(chatGateway);
    }

    @Test
    void 引用只来自真实检索结果() {
        RetrievedChunk chunk = new RetrievedChunk(11L, 22L, 1L, "订单设计.pdf", 2, 0, 3,
                "最大连接数为30", 0.9);
        when(searchService.search(anyString(), any(), any(), any())).thenReturn(List.of(chunk));
        when(chatGateway.generate(anyString(), anyString())).thenReturn("最大连接数为30 [1]");

        var result = service.answer("最大连接数？", List.of(1L), 1L, 5);

        assertThat(result.answer()).contains("[1]");
        assertThat(result.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.index()).isEqualTo(1);
            assertThat(citation.chunkId()).isEqualTo(11L);
            assertThat(citation.documentId()).isEqualTo(22L);
            assertThat(citation.pageNumber()).isEqualTo(3);
        });
        verify(chatGateway).generate(anyString(), org.mockito.ArgumentMatchers.contains("最大连接数为30"));
    }

    @Test
    void 拒绝模型返回不存在的引用编号() {
        RetrievedChunk chunk = new RetrievedChunk(11L, 22L, 1L, "订单设计.pdf", 2, 0, 3,
                "最大连接数为30", 0.9);
        when(searchService.search(anyString(), any(), any(), any())).thenReturn(List.of(chunk));
        when(chatGateway.generate(anyString(), anyString())).thenReturn("错误引用 [2]");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.answer("最大连接数？", List.of(1L), 1L, 5))
                .isInstanceOf(com.teammind.common.exception.BusinessException.class)
                .hasMessageContaining("不存在的证据引用");
    }
}
