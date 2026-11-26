package com.teammind.ai.rag;

import com.teammind.ai.api.Citation;
import com.teammind.ai.api.CitationValidator;
import com.teammind.ai.model.ChatCompletionGateway;
import com.teammind.ai.prompt.RagPrompts;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.api.EvidenceRecorder;
import com.teammind.knowledge.api.RetrievalResult;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.api.SearchStrategy;
import com.teammind.knowledge.application.KnowledgeSearchService;
import com.teammind.observability.application.RagTraceService;
import com.teammind.observability.domain.RagTrace;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class RagAnswerService {
    private final KnowledgeSearchService searchService;
    private final ChatCompletionGateway chatGateway;
    private final EvidenceRecorder evidenceRecorder;
    private final RagTraceService traceService;

    public RagAnswerService(KnowledgeSearchService searchService, ChatCompletionGateway chatGateway,
            EvidenceRecorder evidenceRecorder, RagTraceService traceService) {
        this.searchService = searchService;
        this.chatGateway = chatGateway;
        this.evidenceRecorder = evidenceRecorder;
        this.traceService = traceService;
    }

    public RagAnswer answer(String query, List<Long> knowledgeBaseIds, Long ownerId, Integer topK) {
        return answer(query, knowledgeBaseIds, ownerId, topK, null);
    }

    public RagAnswer answer(String query, List<Long> knowledgeBaseIds, Long ownerId, Integer topK,
            SearchStrategy strategy) {
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "问题不能为空");
        }
        String traceId = UUID.randomUUID().toString().replace("-", "");
        long startedAt = System.nanoTime();
        RagTrace trace = traceService.start(traceId, ownerId, query, knowledgeBaseIds,
                strategy == null ? null : strategy.name());
        int inputTokens = 0;
        int outputTokens = 0;
        try {
            RetrievalResult retrieval = searchService.searchWithTrace(query, knowledgeBaseIds, ownerId, topK,
                    strategy, traceId);
            traceService.updateStrategy(trace.getId(), retrieval.strategy());
            if (retrieval.chunks().isEmpty()) {
                String answer = "当前知识库没有找到足够依据回答该问题。";
                traceService.finish(trace.getId(), true, elapsedMillis(startedAt), answer, 0, 0, null);
                return new RagAnswer(answer, List.of(), traceId, retrieval.strategy());
            }
            RagTraceService.SpanHandle llmSpan = traceService.startSpan(traceId, null, "LLM_GENERATION",
                    "RAG answer, evidence=" + retrieval.chunks().size());
            ChatCompletionGateway.ChatCompletionResult completion;
            try {
                // 引用编号由服务端固定生成，不接受模型传入的文档 ID 或任意引用位置。
                completion = chatGateway.generateWithUsage(RagPrompts.SYSTEM_PROMPT,
                        RagPrompts.buildUserContent(query, retrieval.chunks()));
            } catch (RuntimeException exception) {
                traceService.failSpan(llmSpan, "MODEL_ERROR", exception.getMessage());
                throw exception;
            }
            inputTokens = completion.inputTokens();
            outputTokens = completion.outputTokens();
            String answer = completion.content();
            traceService.finishSpan(llmSpan, "SUCCESS", "answerLength=" + answer.length(),
                    null, null, inputTokens, outputTokens);
            Set<Integer> indexes = CitationValidator.validate(answer, retrieval.chunks().size());
            List<Citation> citations = java.util.stream.IntStream.range(0, retrieval.chunks().size())
                    .mapToObj(index -> toCitation(index + 1, retrieval.chunks().get(index))).toList();
            evidenceRecorder.markCitations(traceId, indexes.stream()
                    .map(index -> retrieval.chunks().get(index - 1).chunkId())
                    .collect(java.util.stream.Collectors.toSet()));
            traceService.finish(trace.getId(), true, elapsedMillis(startedAt), answer,
                    inputTokens, outputTokens, null);
            return new RagAnswer(answer, citations, traceId, retrieval.strategy());
        } catch (RuntimeException exception) {
            traceService.finish(trace.getId(), false, elapsedMillis(startedAt), null,
                    inputTokens, outputTokens, exception.getMessage());
            throw exception;
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private Citation toCitation(int index, RetrievedChunk chunk) {
        return new Citation(index, chunk.chunkId(), chunk.documentId(), chunk.knowledgeBaseId(),
                chunk.documentName(), chunk.documentVersion(), chunk.pageNumber(), chunk.content(), chunk.score(),
                chunk.denseRank(), chunk.bm25Rank(), chunk.denseScore(), chunk.bm25Score(),
                chunk.rrfScore(), chunk.rerankScore());
    }

    public record RagAnswer(String answer, List<Citation> citations, String traceId, SearchStrategy strategy) {
    }
}
