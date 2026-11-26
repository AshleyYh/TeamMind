package com.teammind.knowledge.api;

/**
 * 检索观测端口。知识模块不依赖可观测性实现，通过该端口写入 RAG 检索 Span。
 * 实现方必须保证观测写入失败不影响检索结果。
 */
public interface RetrievalTraceRecorder {

    void record(String traceId, String query, String strategy, long durationMillis,
            int resultCount, boolean success, String errorMessage);
}
