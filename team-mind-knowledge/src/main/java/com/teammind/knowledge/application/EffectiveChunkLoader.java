package com.teammind.knowledge.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.domain.Chunk;
import com.teammind.knowledge.domain.Document;
import com.teammind.knowledge.domain.ProcessingStatus;
import com.teammind.knowledge.mapper.ChunkMapper;
import com.teammind.knowledge.mapper.DocumentMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 统一在召回后再次核验知识库归属和当前版本，避免索引残留产生过期证据。 */
@Component
public class EffectiveChunkLoader {
    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;

    public EffectiveChunkLoader(ChunkMapper chunkMapper, DocumentMapper documentMapper) {
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
    }

    public List<RankedHit> loadByVectorIds(List<ChunkHit> hits, List<Long> knowledgeBaseIds) {
        if (hits.isEmpty()) {
            return List.of();
        }
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .in(Chunk::getVectorId, hits.stream().map(ChunkHit::key).toList()));
        Map<String, Chunk> byVectorId = chunks.stream().collect(Collectors.toMap(Chunk::getVectorId, Function.identity()));
        return loadValid(hits, byVectorId, knowledgeBaseIds);
    }

    public List<RankedHit> loadByChunkIds(List<ChunkHit> hits, List<Long> knowledgeBaseIds) {
        if (hits.isEmpty()) {
            return List.of();
        }
        List<Chunk> chunks = chunkMapper.selectByIds(hits.stream().map(hit -> Long.valueOf(hit.key())).toList());
        Map<String, Chunk> byId = chunks.stream().collect(Collectors.toMap(chunk -> chunk.getId().toString(), Function.identity()));
        return loadValid(hits, byId, knowledgeBaseIds);
    }

    private List<RankedHit> loadValid(List<ChunkHit> hits, Map<String, Chunk> chunkMap,
            List<Long> knowledgeBaseIds) {
        if (chunkMap.isEmpty()) {
            return List.of();
        }
        Map<Long, Document> documents = documentMapper.selectByIds(chunkMap.values().stream()
                        .map(Chunk::getDocumentId).distinct().toList()).stream()
                .filter(document -> ProcessingStatus.COMPLETED.name().equals(document.getProcessingStatus()))
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        return java.util.stream.IntStream.range(0, hits.size()).mapToObj(index -> {
            ChunkHit hit = hits.get(index);
            Chunk chunk = chunkMap.get(hit.key());
            if (chunk == null) {
                return null;
            }
            Document document = documents.get(chunk.getDocumentId());
            if (document == null || !document.getCurrentVersion().equals(chunk.getDocumentVersion())
                    || !knowledgeBaseIds.contains(chunk.getKnowledgeBaseId())
                    || !document.getKnowledgeBaseId().equals(chunk.getKnowledgeBaseId())) {
                return null;
            }
            RetrievedChunk result = new RetrievedChunk(chunk.getId(), document.getId(), chunk.getKnowledgeBaseId(),
                    document.getDocumentName(), chunk.getDocumentVersion(), chunk.getChunkIndex(), chunk.getPageNumber(),
                    chunk.getContent(), chunk.getVectorId(), null, null, null, null, null, null);
            return new RankedHit(index + 1, hit.score(), result);
        }).filter(java.util.Objects::nonNull).toList();
    }

    public record ChunkHit(String key, double score) {
    }

    public record RankedHit(int rank, double score, RetrievedChunk chunk) {
    }
}
