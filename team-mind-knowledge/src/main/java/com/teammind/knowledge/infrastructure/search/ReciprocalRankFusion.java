package com.teammind.knowledge.infrastructure.search;

import com.teammind.knowledge.api.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ReciprocalRankFusion {
    public List<RetrievedChunk> fuse(List<RetrievedChunk> dense, List<RetrievedChunk> bm25, int k) {
        Map<Long, RetrievedChunk> merged = new LinkedHashMap<>();
        addRank(dense, "dense", k, merged);
        addRank(bm25, "bm25", k, merged);
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(RetrievedChunk::rrfScore).reversed()
                        .thenComparing(RetrievedChunk::chunkId))
                .toList();
    }

    private void addRank(List<RetrievedChunk> results, String source, int k,
            Map<Long, RetrievedChunk> merged) {
        for (int index = 0; index < results.size(); index++) {
            RetrievedChunk hit = results.get(index);
            int rank = index + 1;
            double contribution = 1.0 / (k + rank);
            // RRF 只使用每路名次，避免把不同量纲的向量距离和 BM25 分数直接相加。
            merged.compute(hit.chunkId(), (id, previous) -> previous == null
                    ? withSource(hit, source, rank, contribution)
                    : new RetrievedChunk(previous.chunkId(), previous.documentId(), previous.knowledgeBaseId(),
                            previous.documentName(), previous.documentVersion(), previous.chunkIndex(),
                            previous.pageNumber(), previous.content(), previous.vectorId(),
                            previous.denseRank() == null ? (source.equals("dense") ? rank : null) : previous.denseRank(),
                            previous.bm25Rank() == null ? (source.equals("bm25") ? rank : null) : previous.bm25Rank(),
                            previous.denseScore() == null ? (source.equals("dense") ? hit.score() : null) : previous.denseScore(),
                            previous.bm25Score() == null ? (source.equals("bm25") ? hit.score() : null) : previous.bm25Score(),
                            (previous.rrfScore() == null ? 0 : previous.rrfScore()) + contribution,
                            previous.rerankScore()));
        }
    }

    private RetrievedChunk withSource(RetrievedChunk hit, String source, int rank, double contribution) {
        return new RetrievedChunk(hit.chunkId(), hit.documentId(), hit.knowledgeBaseId(), hit.documentName(),
                hit.documentVersion(), hit.chunkIndex(), hit.pageNumber(), hit.content(), hit.vectorId(),
                source.equals("dense") ? rank : null, source.equals("bm25") ? rank : null,
                source.equals("dense") ? hit.score() : null, source.equals("bm25") ? hit.score() : null,
                contribution, null);
    }
}
