package com.teammind.knowledge.api;

import com.teammind.knowledge.domain.Chunk;

import java.util.List;

/** 向量库端口：屏蔽 Qdrant 客户端细节，便于后续替换向量数据库。 */
public interface VectorStoreGateway {
    void ensureCollection(int dimension);
    void upsert(List<Chunk> chunks, List<List<Float>> vectors);
    void deleteByDocumentId(Long documentId);
    List<SearchResult> search(List<Float> queryVector, List<Long> knowledgeBaseIds, int topK, double threshold);

    record SearchResult(String vectorId, double score) {
    }
}
