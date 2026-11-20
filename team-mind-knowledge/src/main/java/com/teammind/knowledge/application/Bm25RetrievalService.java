package com.teammind.knowledge.application;

import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.config.RetrievalProperties;
import com.teammind.knowledge.infrastructure.search.TextTokenizer;
import com.teammind.knowledge.mapper.ChunkMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class Bm25RetrievalService {
    private final ChunkMapper chunkMapper;
    private final EffectiveChunkLoader chunkLoader;
    private final TextTokenizer tokenizer;
    private final RetrievalProperties properties;

    public Bm25RetrievalService(ChunkMapper chunkMapper, EffectiveChunkLoader chunkLoader,
            TextTokenizer tokenizer, RetrievalProperties properties) {
        this.chunkMapper = chunkMapper;
        this.chunkLoader = chunkLoader;
        this.tokenizer = tokenizer;
        this.properties = properties;
    }

    public List<RetrievedChunk> retrieve(String query, List<Long> knowledgeBaseIds, Long ownerId, int limit) {
        List<String> tokens = tokenizer.tokenize(query).stream().distinct().limit(properties.maxTermCount()).toList();
        if (tokens.isEmpty()) {
            return List.of();
        }
        String queryTerms = String.join(" ", tokens);
        // TextTokenizer 已剔除运算符，tsquery 仅接收安全的 OR 表达式。
        String tsQuery = tokens.stream().collect(Collectors.joining(" | "));
        List<ChunkMapper.Bm25Hit> hits = chunkMapper.searchBm25(tsQuery, queryTerms, knowledgeBaseIds,
                ownerId, limit, properties.maxTermCount());
        return chunkLoader.loadByChunkIds(hits.stream()
                        .map(hit -> new EffectiveChunkLoader.ChunkHit(hit.id().toString(), hit.score())).toList(),
                knowledgeBaseIds).stream().map(hit -> {
                    RetrievedChunk chunk = hit.chunk();
                    return new RetrievedChunk(chunk.chunkId(), chunk.documentId(), chunk.knowledgeBaseId(),
                            chunk.documentName(), chunk.documentVersion(), chunk.chunkIndex(), chunk.pageNumber(),
                            chunk.content(), chunk.vectorId(), null, hit.rank(), null, hit.score(), null, null);
                }).toList();
    }
}
