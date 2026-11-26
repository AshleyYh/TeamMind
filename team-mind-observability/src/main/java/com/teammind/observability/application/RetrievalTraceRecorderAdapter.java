package com.teammind.observability.application;

import com.teammind.knowledge.api.RetrievalTraceRecorder;
import org.springframework.stereotype.Component;

/** 把知识模块的检索事件转换为 Trace Span；recordSpan 内部失败开放。 */
@Component
public class RetrievalTraceRecorderAdapter implements RetrievalTraceRecorder {
    private final RagTraceService traceService;

    public RetrievalTraceRecorderAdapter(RagTraceService traceService) {
        this.traceService = traceService;
    }

    @Override
    public void record(String traceId, String query, String strategy, long durationMillis,
            int resultCount, boolean success, String errorMessage) {
        traceService.recordSpan(traceId, null, "RAG_RETRIEVAL", success ? "SUCCESS" : "FAILED",
                durationMillis, "query=" + query + ", strategy=" + strategy,
                "results=" + resultCount, success ? null : "RETRIEVAL_FAILED", errorMessage, 0, 0);
    }
}
