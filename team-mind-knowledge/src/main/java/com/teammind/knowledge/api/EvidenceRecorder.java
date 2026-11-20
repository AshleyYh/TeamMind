package com.teammind.knowledge.api;

import java.util.List;
import java.util.Set;

/** 知识检索只依赖证据端口，避免知识模块反向依赖可观测性实现。 */
public interface EvidenceRecorder {
    void record(String traceId, Long ownerId, String query, List<Long> knowledgeBaseIds,
            SearchStrategy strategy, List<RetrievedChunk> chunks);
    void markCitations(String traceId, Set<Long> chunkIds);
}
