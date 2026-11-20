package com.teammind.knowledge.api;

import java.util.List;

/** 重排序模型端口：仅返回候选索引及模型分数，不允许模型改变证据内容。 */
public interface RerankerGateway {
    List<RerankResult> rerank(String query, List<RetrievedChunk> candidates);

    record RerankResult(int index, double score) {
    }
}
