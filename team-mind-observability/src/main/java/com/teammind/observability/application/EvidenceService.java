package com.teammind.observability.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.teammind.knowledge.api.EvidenceRecorder;
import com.teammind.knowledge.api.RetrievedChunk;
import com.teammind.knowledge.api.SearchStrategy;
import com.teammind.observability.domain.Evidence;
import com.teammind.observability.mapper.EvidenceMapper;
import com.teammind.observability.domain.RagTrace;
import com.teammind.observability.mapper.RagTraceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class EvidenceService implements EvidenceRecorder {
    private final EvidenceMapper evidenceMapper;
    private final RagTraceMapper traceMapper;

    public EvidenceService(EvidenceMapper evidenceMapper, RagTraceMapper traceMapper) {
        this.evidenceMapper = evidenceMapper;
        this.traceMapper = traceMapper;
    }

    @Override
    @Transactional
    public void record(String traceId, Long ownerId, String query, List<Long> knowledgeBaseIds,
            SearchStrategy strategy, List<RetrievedChunk> chunks) {
        RagTrace existing = traceMapper.selectOne(new LambdaQueryWrapper<RagTrace>()
                .eq(RagTrace::getTraceId, traceId));
        if (existing == null) {
            // 独立检索且无命中时也保存记录，返回的 traceId 必须可查询。
            RagTrace trace = new RagTrace();
            trace.setTraceId(traceId);
            trace.setUserId(ownerId);
            trace.setStatus("SUCCESS");
            trace.setQuery(query);
            trace.setKnowledgeBaseIds(knowledgeBaseIds.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(",")));
            trace.setStrategy(strategy.name());
            trace.setCreatedAt(LocalDateTime.now());
            traceMapper.insert(trace);
        }
        for (RetrievedChunk chunk : chunks) {
            Evidence evidence = new Evidence();
            evidence.setTraceId(traceId);
            evidence.setOwnerId(ownerId);
            evidence.setDocumentId(chunk.documentId());
            evidence.setChunkId(chunk.chunkId());
            evidence.setDocumentName(chunk.documentName());
            evidence.setDocumentVersion(chunk.documentVersion());
            evidence.setContent(chunk.content());
            evidence.setPageNumber(chunk.pageNumber());
            evidence.setRetrievalType(strategy == SearchStrategy.HYBRID_RERANK
                    ? SearchStrategy.HYBRID.name() : strategy.name());
            evidence.setDenseRank(chunk.denseRank());
            evidence.setBm25Rank(chunk.bm25Rank());
            evidence.setDenseScore(chunk.denseScore());
            evidence.setBm25Score(chunk.bm25Score());
            evidence.setRrfScore(chunk.rrfScore() != null && chunk.rrfScore() > 0 ? chunk.rrfScore() : null);
            evidence.setRerankScore(chunk.rerankScore());
            evidence.setReranked(chunk.rerankScore() != null);
            evidence.setCited(false);
            evidence.setCreatedAt(LocalDateTime.now());
            evidenceMapper.insert(evidence);
        }
    }

    @Override
    @Transactional
    public void markCitations(String traceId, Set<Long> chunkIds) {
        if (!chunkIds.isEmpty()) {
            Evidence update = new Evidence();
            update.setCited(true);
            evidenceMapper.update(update, new LambdaUpdateWrapper<Evidence>()
                    .eq(Evidence::getTraceId, traceId).in(Evidence::getChunkId, chunkIds));
        }
    }

    public List<Evidence> list(String traceId, Long ownerId) {
        return evidenceMapper.selectList(new LambdaQueryWrapper<Evidence>()
                .eq(Evidence::getTraceId, traceId).eq(Evidence::getOwnerId, ownerId)
                .orderByAsc(Evidence::getId));
    }
}
