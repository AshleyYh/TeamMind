package com.teammind.knowledge.infrastructure.search;

import com.teammind.knowledge.api.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTests {
    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion();

    @Test
    void 同一分块按名次融合且保留两路原始排名() {
        RetrievedChunk first = chunk(1);
        RetrievedChunk second = chunk(2);
        RetrievedChunk third = chunk(3);
        var results = fusion.fuse(List.of(first, second), List.of(third, second), 60);

        assertThat(results).extracting(RetrievedChunk::chunkId).containsExactly(2L, 1L, 3L);
        assertThat(results.getFirst().denseRank()).isEqualTo(2);
        assertThat(results.getFirst().bm25Rank()).isEqualTo(2);
        assertThat(results.getFirst().rrfScore()).isEqualTo(2.0 / 62);
        assertThat(results.get(1).rrfScore()).isEqualTo(1.0 / 61);
    }

    @Test
    void 只在单路出现的分块仍然保留() {
        var results = fusion.fuse(List.of(chunk(1)), List.of(chunk(2)), 60);
        assertThat(results).hasSize(2);
        assertThat(results.getFirst().bm25Rank()).isNull();
        assertThat(results.getLast().denseRank()).isNull();
    }

    private RetrievedChunk chunk(long id) {
        return new RetrievedChunk(id, 1L, 1L, "研发.md", 1, 0, null, "内容" + id,
                "vector-" + id, null, null, null, null, 0d, null);
    }
}
