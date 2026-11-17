package com.teammind.knowledge.application;

import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.api.ParsedDocument;
import com.teammind.knowledge.config.DocumentProperties;
import com.teammind.knowledge.domain.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextChunkerTests {

    @Test
    void 保留页码并按配置生成重叠分块() {
        TextChunker chunker = new TextChunker(new DocumentProperties(64, 10, 10, 100, 1000));
        List<Chunk> chunks = chunker.chunk(1L, 2L, 3, new ParsedDocument(List.of(
                new ParsedDocument.PageContent(4, "a".repeat(200)))));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getContent()).isEqualTo("a".repeat(128));
        assertThat(chunks.get(1).getContent()).isEqualTo("a".repeat(92));
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getDocumentId()).isEqualTo(1L);
            assertThat(chunk.getKnowledgeBaseId()).isEqualTo(2L);
            assertThat(chunk.getDocumentVersion()).isEqualTo(3);
            assertThat(chunk.getPageNumber()).isEqualTo(4);
        });
        assertThat(chunks.stream().map(Chunk::getChunkIndex)).containsExactly(0, 1);
    }

    @Test
    void 拒绝没有文本的文档() {
        TextChunker chunker = new TextChunker(new DocumentProperties(64, 10, 10, 100, 1000));
        assertThatThrownBy(() -> chunker.chunk(1L, 2L, 1,
                new ParsedDocument(List.of(new ParsedDocument.PageContent(null, "  ")))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有");
    }
}
