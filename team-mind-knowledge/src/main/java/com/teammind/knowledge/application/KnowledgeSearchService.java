package com.teammind.knowledge.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.teammind.knowledge.api.EmbeddingGateway;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.api.VectorStoreGateway;
import com.teammind.knowledge.config.QdrantKnowledgeProperties;
import com.teammind.knowledge.domain.Chunk;
import com.teammind.knowledge.domain.Document;
import com.teammind.knowledge.domain.ProcessingStatus;
import com.teammind.knowledge.mapper.ChunkMapper;
import com.teammind.knowledge.mapper.DocumentMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KnowledgeSearchService {
    private final EmbeddingGateway embeddingGateway;
    private final VectorStoreGateway vectorStore;
    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final QdrantKnowledgeProperties properties;

    public KnowledgeSearchService(EmbeddingGateway embeddingGateway, VectorStoreGateway vectorStore,
            ChunkMapper chunkMapper, DocumentMapper documentMapper, KnowledgeBaseService knowledgeBaseService,
            QdrantKnowledgeProperties properties) {
        this.embeddingGateway = embeddingGateway;
        this.vectorStore = vectorStore;
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.properties = properties;
    }

    public List<RetrievedChunk> search(String query, List<Long> knowledgeBaseIds, Long ownerId, Integer topK) {
        if (query == null || query.isBlank()) {
            throw new com.teammind.common.exception.BusinessException(
                    com.teammind.common.api.ErrorCode.VALIDATION_FAILED, "检索问题不能为空");
        }
        knowledgeBaseService.getAccessible(knowledgeBaseIds, ownerId);
        int limit = topK == null ? properties.topK() : Math.min(Math.max(topK, 1), 20);
        List<VectorStoreGateway.SearchResult> hits = vectorStore.search(embeddingGateway.embedQuery(query),
                knowledgeBaseIds, limit, properties.scoreThreshold());
        if (hits.isEmpty()) {
            return List.of();
        }
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .in(Chunk::getVectorId, hits.stream().map(VectorStoreGateway.SearchResult::vectorId).toList()));
        if (chunks.isEmpty()) {
            // 向量库可能存在数据库已清理的残留点，没有对应 Chunk 时直接返回空结果。
            return List.of();
        }
        Map<String, Chunk> chunkMap = chunks.stream()
                .collect(Collectors.toMap(Chunk::getVectorId, Function.identity()));
        List<Document> documents = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .in(Document::getId, chunks.stream().map(Chunk::getDocumentId).distinct().toList())
                .eq(Document::getProcessingStatus, ProcessingStatus.COMPLETED.name()));
        Map<Long, Document> documentMap = documents.stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        // 数据库中的当前有效文档是最终依据，避免向量库残留或失败任务导致越权、过期引用。
        return hits.stream().map(hit -> {
                    Chunk chunk = chunkMap.get(hit.vectorId());
                    if (chunk == null) {
                        return null;
                    }
                    Document document = documentMap.get(chunk.getDocumentId());
                    if (document == null || !document.getCurrentVersion().equals(chunk.getDocumentVersion())
                            || !knowledgeBaseIds.contains(chunk.getKnowledgeBaseId())) {
                        return null;
                    }
                    return new RetrievedChunk(chunk.getId(), document.getId(), chunk.getKnowledgeBaseId(),
                            document.getDocumentName(), chunk.getDocumentVersion(), chunk.getChunkIndex(),
                            chunk.getPageNumber(), chunk.getContent(), hit.score());
                }).filter(java.util.Objects::nonNull).toList();
    }
}
