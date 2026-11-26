package com.teammind.ai.api;

/**
 * 对外返回的证据引用。编号由服务端按证据出现顺序生成，模型不能指定任意文档或分块。
 * Agent 与普通 RAG 共用同一结构，保证前端引用展示逻辑一致。
 */
public record Citation(
        int index,
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        String documentName,
        Integer documentVersion,
        Integer pageNumber,
        String content,
        double score,
        Integer denseRank,
        Integer bm25Rank,
        Double denseScore,
        Double bm25Score,
        Double rrfScore,
        Double rerankScore) {
}
