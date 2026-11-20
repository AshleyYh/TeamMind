package com.teammind.ai.rag;

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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RagAnswerService {
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

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
        try {
            RetrievalResult retrieval = searchService.searchWithTrace(query, knowledgeBaseIds, ownerId, topK,
                    strategy, traceId);
            traceService.updateStrategy(trace.getId(), retrieval.strategy());
            if (retrieval.chunks().isEmpty()) {
                String answer = "当前知识库没有找到足够依据回答该问题。";
                traceService.finish(trace.getId(), true, (System.nanoTime() - startedAt) / 1_000_000, answer);
                return new RagAnswer(answer, List.of(), traceId, retrieval.strategy());
            }
            // 引用编号由服务端固定生成，不接受模型传入的文档 ID 或任意引用位置。
            String answer = chatGateway.generate(RagPrompts.SYSTEM_PROMPT,
                    RagPrompts.buildUserContent(query, retrieval.chunks()));
            Set<Integer> indexes = validateCitationIndexes(answer, retrieval.chunks().size());
            List<Citation> citations = java.util.stream.IntStream.range(0, retrieval.chunks().size())
                    .mapToObj(index -> toCitation(index + 1, retrieval.chunks().get(index))).toList();
            evidenceRecorder.markCitations(traceId, indexes.stream()
                    .map(index -> retrieval.chunks().get(index - 1).chunkId())
                    .collect(java.util.stream.Collectors.toSet()));
            traceService.finish(trace.getId(), true, (System.nanoTime() - startedAt) / 1_000_000, answer);
            return new RagAnswer(answer, citations, traceId, retrieval.strategy());
        } catch (RuntimeException exception) {
            traceService.finish(trace.getId(), false, (System.nanoTime() - startedAt) / 1_000_000, null);
            throw exception;
        }
    }

    private Set<Integer> validateCitationIndexes(String answer, int evidenceCount) {
        Set<Integer> indexes = new HashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            int index;
            try {
                index = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                        "模型返回了不存在的证据引用，已拒绝本次回答");
            }
            if (index < 1 || index > evidenceCount) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                        "模型返回了不存在的证据引用，已拒绝本次回答");
            }
            indexes.add(index);
        }
        return indexes;
    }

    private Citation toCitation(int index, RetrievedChunk chunk) {
        return new Citation(index, chunk.chunkId(), chunk.documentId(), chunk.knowledgeBaseId(),
                chunk.documentName(), chunk.documentVersion(), chunk.pageNumber(), chunk.content(), chunk.score(),
                chunk.denseRank(), chunk.bm25Rank(), chunk.denseScore(), chunk.bm25Score(),
                chunk.rrfScore(), chunk.rerankScore());
    }

    public record RagAnswer(String answer, List<Citation> citations, String traceId, SearchStrategy strategy) {
    }

    public record Citation(int index, Long chunkId, Long documentId, Long knowledgeBaseId,
            String documentName, Integer documentVersion, Integer pageNumber, String content, double score,
            Integer denseRank, Integer bm25Rank, Double denseScore, Double bm25Score,
            Double rrfScore, Double rerankScore) {
    }
}
