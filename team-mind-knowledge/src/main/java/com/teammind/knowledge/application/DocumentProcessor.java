package com.teammind.knowledge.application;

import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.api.EmbeddingGateway;
import com.teammind.knowledge.api.ParsedDocument;
import com.teammind.knowledge.api.VectorStoreGateway;
import com.teammind.knowledge.config.DocumentProperties;
import com.teammind.knowledge.domain.Chunk;
import com.teammind.knowledge.domain.Document;
import com.teammind.knowledge.domain.ProcessingStatus;
import com.teammind.knowledge.infrastructure.parser.DocumentParserRegistry;
import com.teammind.knowledge.infrastructure.search.TextTokenizer;
import com.teammind.knowledge.infrastructure.storage.LocalDocumentStorage;
import com.teammind.knowledge.mapper.ChunkMapper;
import com.teammind.knowledge.mapper.DocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class DocumentProcessor {
    private static final Logger log = LoggerFactory.getLogger(DocumentProcessor.class);
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final LocalDocumentStorage storage;
    private final DocumentParserRegistry parserRegistry;
    private final TextChunker chunker;
    private final TextTokenizer tokenizer;
    private final EmbeddingGateway embeddingGateway;
    private final VectorStoreGateway vectorStore;
    private final DocumentProperties properties;

    public DocumentProcessor(DocumentMapper documentMapper, ChunkMapper chunkMapper,
            LocalDocumentStorage storage, DocumentParserRegistry parserRegistry, TextChunker chunker,
            TextTokenizer tokenizer, EmbeddingGateway embeddingGateway, VectorStoreGateway vectorStore,
            DocumentProperties properties) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.storage = storage;
        this.parserRegistry = parserRegistry;
        this.chunker = chunker;
        this.tokenizer = tokenizer;
        this.embeddingGateway = embeddingGateway;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    @Async("documentTaskExecutor")
    public void process(Long documentId, int version) {
        Document document = documentMapper.selectById(documentId);
        if (document == null || document.getCurrentVersion() != version) {
            return;
        }
        try {
            if (!claim(document)) {
                return;
            }
            // 任务获取成功后重新读取，避免使用上传事务前的旧文件路径或旧版本。
            document = documentMapper.selectById(documentId);
            if (document == null || document.getCurrentVersion() != version) {
                return;
            }
            // 重试时先清除上次可能残留的向量和分块，保证同一文档可重复入库。
            vectorStore.deleteByDocumentId(documentId);
            chunkMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chunk>()
                    .eq(Chunk::getDocumentId, documentId));
            ParsedDocument parsed;
            try (InputStream inputStream = storage.open(Path.of(document.getFilePath()))) {
                parsed = parserRegistry.get(document.getFileType()).parse(inputStream);
            }
            List<Chunk> chunks = chunker.chunk(documentId, document.getKnowledgeBaseId(),
                    document.getCurrentVersion(), parsed);
            chunks.forEach(chunk -> chunk.setSearchTokens(String.join(" ", tokenizer.tokenize(chunk.getContent()))));
            vectorStore.ensureCollection(embeddingGateway.dimension());
            for (int start = 0; start < chunks.size(); start += properties.embeddingBatchSize()) {
                List<Chunk> batch = chunks.subList(start,
                        Math.min(chunks.size(), start + properties.embeddingBatchSize()));
                List<java.util.List<Float>> vectors = embeddingGateway.embedDocuments(
                        batch.stream().map(Chunk::getContent).toList());
                // 向量库会为分块生成确定性的 vectorId，必须先完成 upsert 再写数据库，避免向量 ID 为空。
                vectorStore.upsert(batch, vectors);
                batch.forEach(chunk -> chunk.setCreatedAt(LocalDateTime.now()));
                chunkMapper.insert(batch);
            }
            markCompleted(documentId, version);
            log.info("文档处理完成：documentId={}，分块数={}", documentId, chunks.size());
        } catch (Exception exception) {
            try {
                vectorStore.deleteByDocumentId(documentId);
            } catch (Exception cleanupException) {
                log.warn("清理失败文档的向量失败：documentId={}", documentId, cleanupException);
            }
            markFailed(documentId, version, exception);
            log.error("文档处理失败：documentId={}", documentId, exception);
        }
    }

    private boolean claim(Document document) {
        Document update = new Document();
        update.setId(document.getId());
        update.setProcessingStatus(ProcessingStatus.PROCESSING.name());
        update.setFailureReason(null);
        update.setUpdatedAt(LocalDateTime.now());
        // 状态条件更新相当于轻量任务锁，避免多个请求同时执行同一文档。
        int changed = documentMapper.update(update, new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Document>()
                .eq(Document::getId, document.getId()).eq(Document::getCurrentVersion, document.getCurrentVersion())
                .ne(Document::getProcessingStatus, ProcessingStatus.PROCESSING.name()));
        document.setProcessingStatus(changed == 1 ? ProcessingStatus.PROCESSING.name() : document.getProcessingStatus());
        return changed == 1;
    }

    private void markCompleted(Long documentId, int version) {
        Document update = new Document();
        update.setId(documentId);
        update.setProcessingStatus(ProcessingStatus.COMPLETED.name());
        update.setUpdatedAt(LocalDateTime.now());
        documentMapper.update(update, new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Document>()
                .eq(Document::getId, documentId).eq(Document::getCurrentVersion, version)
                .eq(Document::getProcessingStatus, ProcessingStatus.PROCESSING.name()));
    }

    private void markFailed(Long documentId, int version, Exception exception) {
        String reason = exception instanceof BusinessException ? exception.getMessage() : "文档处理失败，请查看服务端日志";
        Document update = new Document();
        update.setId(documentId);
        update.setProcessingStatus(ProcessingStatus.FAILED.name());
        update.setFailureReason(reason == null ? "未知错误" : reason.substring(0, Math.min(500, reason.length())));
        update.setUpdatedAt(LocalDateTime.now());
        documentMapper.update(update, new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Document>()
                .eq(Document::getId, documentId).eq(Document::getCurrentVersion, version)
                .eq(Document::getProcessingStatus, ProcessingStatus.PROCESSING.name()));
    }
}
