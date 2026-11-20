package com.teammind.knowledge.application;

import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.knowledge.api.EvidenceRecorder;
import com.teammind.knowledge.api.RerankerGateway;
import com.teammind.knowledge.api.RetrievalResult;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.api.SearchStrategy;
import com.teammind.knowledge.config.QdrantKnowledgeProperties;
import com.teammind.knowledge.config.RetrievalProperties;
import com.teammind.knowledge.infrastructure.search.ReciprocalRankFusion;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class KnowledgeSearchService {
    private final DenseRetrievalService denseRetrieval;
    private final Bm25RetrievalService bm25Retrieval;
    private final KnowledgeBaseService knowledgeBaseService;
    private final QdrantKnowledgeProperties vectorProperties;
    private final RetrievalProperties properties;
    private final ReciprocalRankFusion fusion;
    private final RerankerGateway reranker;
    private final EvidenceRecorder evidenceRecorder;
    private final ExecutorService retrievalTaskExecutor;

    public KnowledgeSearchService(DenseRetrievalService denseRetrieval, Bm25RetrievalService bm25Retrieval,
            KnowledgeBaseService knowledgeBaseService, QdrantKnowledgeProperties vectorProperties,
            RetrievalProperties properties, ReciprocalRankFusion fusion, RerankerGateway reranker,
            EvidenceRecorder evidenceRecorder, ExecutorService retrievalTaskExecutor) {
        this.denseRetrieval = denseRetrieval;
        this.bm25Retrieval = bm25Retrieval;
        this.knowledgeBaseService = knowledgeBaseService;
        this.vectorProperties = vectorProperties;
        this.properties = properties;
        this.fusion = fusion;
        this.reranker = reranker;
        this.evidenceRecorder = evidenceRecorder;
        this.retrievalTaskExecutor = retrievalTaskExecutor;
    }

    public List<RetrievedChunk> search(String query, List<Long> knowledgeBaseIds, Long ownerId, Integer topK) {
        return searchWithTrace(query, knowledgeBaseIds, ownerId, topK, null).chunks();
    }

    public RetrievalResult searchWithTrace(String query, List<Long> knowledgeBaseIds, Long ownerId, Integer topK,
            SearchStrategy requestedStrategy) {
        return searchWithTrace(query, knowledgeBaseIds, ownerId, topK, requestedStrategy,
                UUID.randomUUID().toString().replace("-", ""));
    }

    public RetrievalResult searchWithTrace(String query, List<Long> knowledgeBaseIds, Long ownerId, Integer topK,
            SearchStrategy requestedStrategy, String traceId) {
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "检索问题不能为空");
        }
        knowledgeBaseService.getAccessible(knowledgeBaseIds, ownerId);
        int limit = topK == null ? vectorProperties.topK() : Math.min(Math.max(topK, 1), 20);
        SearchStrategy strategy = requestedStrategy == null ? properties.defaultStrategy() : requestedStrategy;
        List<RetrievedChunk> results = switch (strategy) {
            case DENSE -> denseRetrieval.retrieve(query, knowledgeBaseIds, ownerId,
                    Math.min(properties.denseCandidateSize(), limit), vectorProperties.scoreThreshold());
            case BM25 -> bm25Retrieval.retrieve(query, knowledgeBaseIds, ownerId,
                    Math.min(properties.bm25CandidateSize(), limit));
            case HYBRID, HYBRID_RERANK -> hybrid(query, knowledgeBaseIds, ownerId,
                    strategy == SearchStrategy.HYBRID_RERANK);
        };
        List<RetrievedChunk> topResults = results.stream().limit(limit).toList();
        evidenceRecorder.record(traceId, ownerId, query, knowledgeBaseIds, strategy, topResults);
        return new RetrievalResult(traceId, strategy, topResults);
    }

    private long remainingMillis(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("并行检索超时");
        }
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private List<RetrievedChunk> hybrid(String query, List<Long> knowledgeBaseIds, Long ownerId, boolean rerank) {
        Future<List<RetrievedChunk>> denseTask = retrievalTaskExecutor.submit(() -> denseRetrieval.retrieve(
                query, knowledgeBaseIds, ownerId, properties.denseCandidateSize(), vectorProperties.scoreThreshold()));
        Future<List<RetrievedChunk>> bm25Task = retrievalTaskExecutor.submit(() -> bm25Retrieval.retrieve(
                query, knowledgeBaseIds, ownerId, properties.bm25CandidateSize()));
        try {
            // 两路共享同一截止时间，避免顺序等待造成两倍超时。
            long deadline = System.nanoTime() + properties.parallelTimeout().toNanos();
            List<RetrievedChunk> dense = denseTask.get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            List<RetrievedChunk> bm25 = bm25Task.get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            // 两路同时召回，单路失败不能悄悄退化为另一种策略。
            List<RetrievedChunk> fused = fusion.fuse(dense, bm25, properties.rrfK());
            if (!rerank) {
                return fused;
            }
            List<RetrievedChunk> candidates = fused.stream().limit(properties.rerankCandidateSize()).toList();
            // 不信任模型实现：越界、重复、非有限分数都在服务层统一丢弃。
            List<RerankerGateway.RerankResult> ranks = reranker.rerank(query, candidates).stream()
                    .filter(rank -> rank.index() >= 0 && rank.index() < candidates.size()
                            && Double.isFinite(rank.score()))
                    .sorted(Comparator.comparingDouble(RerankerGateway.RerankResult::score).reversed())
                    .toList();
            List<RetrievedChunk> results = new ArrayList<>();
            java.util.Set<Integer> usedIndexes = new java.util.HashSet<>();
            ranks.stream().filter(rank -> usedIndexes.add(rank.index()))
                    .map(rank -> candidates.get(rank.index()).withRerankScore(rank.score()))
                    .forEach(results::add);
            return List.copyOf(results);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "并行检索被中断", exception);
        } catch (TimeoutException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "并行检索超时", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "检索服务执行失败", exception);
        } finally {
            denseTask.cancel(true);
            bm25Task.cancel(true);
        }
    }
}
