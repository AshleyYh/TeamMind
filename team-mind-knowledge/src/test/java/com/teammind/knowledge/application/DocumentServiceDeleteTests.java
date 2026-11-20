package com.teammind.knowledge.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.teammind.knowledge.api.VectorStoreGateway;
import com.teammind.knowledge.domain.Document;
import com.teammind.knowledge.domain.ProcessingStatus;
import com.teammind.knowledge.infrastructure.storage.LocalDocumentStorage;
import com.teammind.knowledge.mapper.ChunkMapper;
import com.teammind.knowledge.mapper.DocumentMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentServiceDeleteTests {
    @Test
    void 删除文档使用逻辑删除并清理向量和分块() {
        DocumentMapper documents = mock(DocumentMapper.class);
        ChunkMapper chunks = mock(ChunkMapper.class);
        VectorStoreGateway vectors = mock(VectorStoreGateway.class);
        Document document = new Document();
        document.setId(11L);
        document.setKnowledgeBaseId(1L);
        document.setDeleted(false);
        document.setProcessingStatus(ProcessingStatus.COMPLETED.name());
        when(documents.selectById(11L)).thenReturn(document);
        DocumentService service = service(documents, chunks, vectors);
        service.delete(11L, 1L);
        verify(vectors).deleteByDocumentId(11L);
        verify(chunks).delete(any(Wrapper.class));
        verify(documents).deleteById(11L);
        assertThat(document.getDeleted()).isFalse();
    }

    @Test
    void 文档处理中拒绝删除() {
        DocumentMapper documents = mock(DocumentMapper.class);
        Document document = new Document();
        document.setId(11L);
        document.setKnowledgeBaseId(1L);
        document.setProcessingStatus(ProcessingStatus.PROCESSING.name());
        when(documents.selectById(11L)).thenReturn(document);
        DocumentService service = service(documents, mock(ChunkMapper.class), mock(VectorStoreGateway.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.delete(11L, 1L))
                .hasMessageContaining("正在处理");
    }

    private DocumentService service(DocumentMapper documents, ChunkMapper chunks, VectorStoreGateway vectors) {
        return new DocumentService(documents, chunks, mock(KnowledgeBaseService.class),
                mock(LocalDocumentStorage.class), mock(DocumentProcessor.class), vectors,
                new com.teammind.knowledge.config.DocumentProperties(64, 10, 10, 100, 1000));
    }
}
