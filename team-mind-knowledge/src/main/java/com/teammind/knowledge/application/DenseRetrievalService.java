package com.teammind.knowledge.application;

import com.teammind.knowledge.api.EmbeddingGateway;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.api.VectorStoreGateway;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DenseRetrievalService {
    private final EmbeddingGateway embeddingGateway;
    private final VectorStoreGateway vectorStore;
    private final EffectiveChunkLoader chunkLoader;

    public DenseRetrievalService(EmbeddingGateway embeddingGateway, VectorStoreGateway vectorStore,
            EffectiveChunkLoader chunkLoader) {
        this.embeddingGateway = embeddingGateway;
        this.vectorStore = vectorStore;
        this.chunkLoader = chunkLoader;
    }

    public List<RetrievedChunk> retrieve(String query, List<Long> knowledgeBaseIds, Long ownerId, int limit,
            double scoreThreshold) {
        List<VectorStoreGateway.SearchResult> hits = vectorStore.search(embeddingGateway.embedQuery(query),
                knowledgeBaseIds, limit, scoreThreshold);
        return chunkLoader.loadByVectorIds(hits.stream()
                        .map(hit -> new EffectiveChunkLoader.ChunkHit(hit.vectorId(), hit.score())).toList(),
                knowledgeBaseIds).stream().map(hit -> {
                    RetrievedChunk chunk = hit.chunk();
                    return new RetrievedChunk(chunk.chunkId(), chunk.documentId(), chunk.knowledgeBaseId(),
                            chunk.documentName(), chunk.documentVersion(), chunk.chunkIndex(), chunk.pageNumber(),
                            chunk.content(), chunk.vectorId(), hit.rank(), null, hit.score(), null, null, null);
                }).toList();
    }
}
