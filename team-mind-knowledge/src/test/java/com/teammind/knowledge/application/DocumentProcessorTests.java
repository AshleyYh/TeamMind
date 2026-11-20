package com.teammind.knowledge.application;

import com.teammind.knowledge.api.EmbeddingGateway;
import com.teammind.knowledge.api.ParsedDocument;
import com.teammind.knowledge.api.VectorStoreGateway;
import com.teammind.knowledge.config.DocumentProperties;
import com.teammind.knowledge.domain.Chunk;
import com.teammind.knowledge.domain.Document;
import com.teammind.knowledge.domain.ProcessingStatus;
import com.teammind.knowledge.infrastructure.parser.DocumentParser;
import com.teammind.knowledge.infrastructure.parser.DocumentParserRegistry;
import com.teammind.knowledge.infrastructure.storage.LocalDocumentStorage;
import com.teammind.knowledge.mapper.ChunkMapper;
import com.teammind.knowledge.mapper.DocumentMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentProcessorTests {

    @Test
    void 文档按解析分块向量化入库的顺序处理() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        LocalDocumentStorage storage = mock(LocalDocumentStorage.class);
        DocumentParser parser = mock(DocumentParser.class);
        DocumentParserRegistry registry = mock(DocumentParserRegistry.class);
        EmbeddingGateway embedding = mock(EmbeddingGateway.class);
        VectorStoreGateway vectors = mock(VectorStoreGateway.class);
        TextChunker chunker = new TextChunker(new DocumentProperties(64, 10, 10, 100, 1000));
        Document document = document();
        when(documentMapper.selectById(1L)).thenReturn(document);
        when(documentMapper.update(any(Document.class), any())).thenReturn(1);
        when(storage.open(any())).thenReturn(new ByteArrayInputStream("内容".getBytes(StandardCharsets.UTF_8)));
        when(registry.get("md")).thenReturn(parser);
        when(parser.parse(any())).thenReturn(new ParsedDocument(List.of(
                new ParsedDocument.PageContent(null, "研发文档"))));
        when(embedding.dimension()).thenReturn(2);
        when(embedding.embedDocuments(anyList())).thenReturn(List.of(List.of(0.1F, 0.2F)));
        DocumentProcessor processor = new DocumentProcessor(documentMapper, chunkMapper, storage,
                registry, chunker, new com.teammind.knowledge.infrastructure.search.TextTokenizer(),
                embedding, vectors, new DocumentProperties(64, 10, 10, 100, 1000));

        processor.process(1L, 1);

        var order = inOrder(vectors, chunkMapper, embedding);
        order.verify(vectors).deleteByDocumentId(1L);
        order.verify(chunkMapper).delete(any());
        order.verify(embedding).dimension();
        order.verify(vectors).ensureCollection(2);
        order.verify(embedding).embedDocuments(List.of("研发文档"));
        // 向量库生成 vectorId 后，分块才能写入数据库。
        order.verify(vectors).upsert(anyList(), anyList());
        order.verify(chunkMapper).insert(anyList());
    }

    @Test
    void 旧版本任务不会处理当前文档() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        when(documentMapper.selectById(1L)).thenReturn(document());
        DocumentProcessor processor = new DocumentProcessor(documentMapper, mock(ChunkMapper.class),
                mock(LocalDocumentStorage.class), mock(DocumentParserRegistry.class), mock(TextChunker.class),
                mock(com.teammind.knowledge.infrastructure.search.TextTokenizer.class),
                mock(EmbeddingGateway.class), mock(VectorStoreGateway.class),
                new DocumentProperties(64, 10, 10, 100, 1000));

        processor.process(1L, 2);

        // 只允许一次版本检查，不能继续更新旧版本状态或触发外部服务。
        org.mockito.Mockito.verify(documentMapper).selectById(1L);
        org.mockito.Mockito.verifyNoMoreInteractions(documentMapper);
    }

    private Document document() {
        Document document = new Document();
        document.setId(1L);
        document.setKnowledgeBaseId(2L);
        document.setCurrentVersion(1);
        document.setProcessingStatus(ProcessingStatus.PENDING.name());
        document.setFileType("md");
        document.setFilePath("test.md");
        return document;
    }
}
