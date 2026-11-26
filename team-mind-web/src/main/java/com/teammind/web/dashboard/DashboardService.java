package com.teammind.web.dashboard;

import com.teammind.observability.application.RagTraceService;
import com.teammind.observability.domain.RagTrace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** 首页统计的轻量聚合查询。只做计数和最近执行，不引入新的业务表。 */
@Service
public class DashboardService {
    private final JdbcTemplate jdbcTemplate;
    private final RagTraceService traceService;

    public DashboardService(JdbcTemplate jdbcTemplate, RagTraceService traceService) {
        this.jdbcTemplate = jdbcTemplate;
        this.traceService = traceService;
    }

    public DashboardStats stats(Long ownerId) {
        return new DashboardStats(
                count("SELECT COUNT(*) FROM knowledge_base WHERE owner_id = ? AND NOT deleted", ownerId),
                count("""
                        SELECT COUNT(*) FROM knowledge_document d
                        JOIN knowledge_base b ON d.knowledge_base_id = b.id
                        WHERE b.owner_id = ? AND NOT b.deleted AND NOT d.deleted
                        """, ownerId),
                count("SELECT COUNT(*) FROM ai_conversation WHERE user_id = ? AND NOT deleted", ownerId),
                count("SELECT COUNT(*) FROM ai_memory WHERE user_id = ? AND NOT deleted", ownerId),
                count("SELECT COUNT(*) FROM ai_trace WHERE user_id = ?", ownerId),
                count("SELECT COUNT(*) FROM ai_evaluation_run WHERE owner_id = ?", ownerId),
                count("SELECT COUNT(*) FROM ai_trace WHERE user_id = ? AND status = 'FAILED'", ownerId),
                traceService.list(ownerId, 5));
    }

    private long count(String sql, Long ownerId) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, ownerId);
        return value == null ? 0 : value;
    }

    public record DashboardStats(
            long knowledgeBaseCount,
            long documentCount,
            long conversationCount,
            long memoryCount,
            long traceCount,
            long evaluationRunCount,
            long failedTraceCount,
            List<RagTrace> recentTraces) {
    }
}
