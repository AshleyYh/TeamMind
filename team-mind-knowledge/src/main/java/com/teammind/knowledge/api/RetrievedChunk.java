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
        double score
) {
}
