package com.teammind.knowledge.api;

import java.util.List;

public record RetrievalResult(String traceId, SearchStrategy strategy, List<RetrievedChunk> chunks) {
}
