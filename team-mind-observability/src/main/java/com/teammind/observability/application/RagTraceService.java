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
        return start(traceId, ownerId, query, knowledgeBaseIds, strategy, null);
    }

    /** Agent 需要把 Trace 关联到会话；会话必须已存在，否则数据库外键会拒绝写入。 */
    public RagTrace start(String traceId, Long ownerId, String query, List<Long> knowledgeBaseIds,
            String strategy, String conversationId) {
        RagTrace trace = new RagTrace();
        trace.setTraceId(traceId);
        trace.setUserId(ownerId);
        trace.setConversationId(conversationId);
        trace.setStatus("RUNNING");
        trace.setQuery(query);
        trace.setKnowledgeBaseIds(knowledgeBaseIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        trace.setStrategy(strategy);
        trace.setCreatedAt(LocalDateTime.now());
        traceMapper.insert(trace);
        return trace;
    }

    public void updateStrategy(Long id, com.teammind.knowledge.api.SearchStrategy strategy) {
        updateStrategy(id, strategy.name());
    }

    /** Agent 需要记录任务类型等非检索策略信息，因此提供字符串重载。 */
    public void updateStrategy(Long id, String strategy) {
        RagTrace update = new RagTrace();
        update.setId(id);
        update.setStrategy(strategy);
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
