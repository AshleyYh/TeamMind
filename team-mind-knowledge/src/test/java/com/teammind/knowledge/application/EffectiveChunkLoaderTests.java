package com.teammind.knowledge.application;

import com.teammind.knowledge.domain.Chunk;
import com.teammind.knowledge.domain.Document;
import com.teammind.knowledge.domain.ProcessingStatus;
import com.teammind.knowledge.mapper.ChunkMapper;
import com.teammind.knowledge.mapper.DocumentMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EffectiveChunkLoaderTests {
    private final ChunkMapper chunkMapper = mock(ChunkMapper.class);
    private final DocumentMapper documentMapper = mock(DocumentMapper.class);
    private final EffectiveChunkLoader loader = new EffectiveChunkLoader(chunkMapper, documentMapper);

    @Test
    void 过期版本和失败文档不能成为证据() {
        Chunk valid = chunk(1, 2);
        Chunk stale = chunk(2, 1);
        Chunk failed = chunk(3, 1);
        when(chunkMapper.selectList(any())).thenReturn(List.of(valid, stale, failed));
        when(documentMapper.selectByIds(any())).thenReturn(List.of(document(1, 2, "COMPLETED"),
                document(3, 1, "FAILED")));
        var results = loader.loadByVectorIds(List.of(new EffectiveChunkLoader.ChunkHit("v1", 0.9),
                new EffectiveChunkLoader.ChunkHit("v2", 0.8), new EffectiveChunkLoader.ChunkHit("v3", 0.7)), List.of(1L));
        assertThat(results).singleElement().satisfies(hit -> {
            assertThat(hit.chunk().chunkId()).isEqualTo(1L);
            assertThat(hit.chunk().documentVersion()).isEqualTo(2);
            assertThat(hit.rank()).isEqualTo(1);
        });
    }

    @Test
    void 未选中的知识库不会进入结果() {
        when(chunkMapper.selectByIds(any())).thenReturn(List.of(chunk(1, 1)));
        when(documentMapper.selectByIds(any())).thenReturn(List.of(document(1, 1, "COMPLETED")));
        assertThat(loader.loadByChunkIds(List.of(new EffectiveChunkLoader.ChunkHit("1", 1)), List.of(2L))).isEmpty();
    }

    private Chunk chunk(long id, int version) {
        Chunk chunk = new Chunk();
        chunk.setId(id);
        chunk.setDocumentId(id);
        chunk.setKnowledgeBaseId(1L);
        chunk.setDocumentVersion(version);
        chunk.setChunkIndex(0);
        chunk.setContent("研发文档");
        chunk.setVectorId("v" + id);
        return chunk;
    }

    private Document document(long id, int version, String status) {
        Document document = new Document();
        document.setId(id);
        document.setKnowledgeBaseId(1L);
        document.setDocumentName("研发.md");
        document.setCurrentVersion(version);
        document.setProcessingStatus(status);
        return document;
    }
}
