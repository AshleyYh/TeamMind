package com.teammind.observability.application;

import com.teammind.observability.domain.RagTrace;
import com.teammind.observability.mapper.RagTraceMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagTraceService {
    private final RagTraceMapper traceMapper;

    public RagTraceService(RagTraceMapper traceMapper) {
        this.traceMapper = traceMapper;
    }

    public RagTrace start(String traceId, Long ownerId, String query, List<Long> knowledgeBaseIds, String strategy) {
        RagTrace trace = new RagTrace();
        trace.setTraceId(traceId);
        trace.setUserId(ownerId);
        trace.setStatus("RUNNING");
        trace.setQuery(query);
        trace.setKnowledgeBaseIds(knowledgeBaseIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        trace.setStrategy(strategy);
        trace.setCreatedAt(LocalDateTime.now());
        traceMapper.insert(trace);
        return trace;
    }

    public void updateStrategy(Long id, com.teammind.knowledge.api.SearchStrategy strategy) {
        RagTrace update = new RagTrace();
        update.setId(id);
        update.setStrategy(strategy.name());
        traceMapper.updateById(update);
    }

    public void finish(Long id, boolean success, long durationMillis, String answer) {
        RagTrace update = new RagTrace();
        update.setId(id);
        update.setStatus(success ? "SUCCESS" : "FAILED");
        update.setTotalDuration(durationMillis);
        update.setAnswer(answer);
        traceMapper.updateById(update);
    }
}
