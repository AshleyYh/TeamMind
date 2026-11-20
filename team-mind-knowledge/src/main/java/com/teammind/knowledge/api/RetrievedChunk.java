package com.teammind.knowledge.api;

public record RetrievedChunk(
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        String documentName,
        Integer documentVersion,
        Integer chunkIndex,
        Integer pageNumber,
        String content,
        String vectorId,
        Integer denseRank,
        Integer bm25Rank,
        Double denseScore,
        Double bm25Score,
        Double rrfScore,
        Double rerankScore
) {
    /** 兼容基础 RAG 的稠密检索结果，同时保留统一证据结构。 */
    public RetrievedChunk(Long chunkId, Long documentId, Long knowledgeBaseId, String documentName,
            Integer documentVersion, Integer chunkIndex, Integer pageNumber, String content, double score) {
        this(chunkId, documentId, knowledgeBaseId, documentName, documentVersion, chunkIndex, pageNumber,
                content, null, null, null, score, null, null, null);
    }

    public double score() {
        return rerankScore != null ? rerankScore : rrfScore != null ? rrfScore
                : denseScore != null ? denseScore : bm25Score == null ? 0 : bm25Score;
    }

    public RetrievedChunk withRerankScore(double score) {
        return new RetrievedChunk(chunkId, documentId, knowledgeBaseId, documentName, documentVersion,
                chunkIndex, pageNumber, content, vectorId, denseRank, bm25Rank, denseScore, bm25Score,
                rrfScore, score);
    }
}
