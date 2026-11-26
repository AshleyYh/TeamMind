package com.teammind.observability.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.observability.domain.RagTrace;
import com.teammind.observability.domain.TraceSpan;
import com.teammind.observability.mapper.RagTraceMapper;
import com.teammind.observability.mapper.TraceSpanMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Trace 与 Span 记录服务。
 *
 * <p>Span 写入采用失败开放策略：观测数据写入失败只记录日志，不阻断业务回答。
 * Trace 本身的创建和收尾仍与请求生命周期绑定，用于保证每个响应都能返回 traceId。</p>
 */
@Service
public class RagTraceService {
    private static final Logger log = LoggerFactory.getLogger(RagTraceService.class);
    private static final int SUMMARY_CHARS = 2000;

    private final RagTraceMapper traceMapper;
    private final TraceSpanMapper spanMapper;

    public RagTraceService(RagTraceMapper traceMapper, TraceSpanMapper spanMapper) {
        this.traceMapper = traceMapper;
        this.spanMapper = spanMapper;
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
        trace.setKnowledgeBaseIds(knowledgeBaseIds == null ? "" : knowledgeBaseIds.stream()
                .map(String::valueOf).collect(Collectors.joining(",")));
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
        finish(id, success, durationMillis, answer, 0, 0, null);
    }

    /** 结束 Trace，同时记录耗时、Token 和失败原因。 */
    public void finish(Long id, boolean success, long durationMillis, String answer,
            int inputTokens, int outputTokens, String errorMessage) {
        RagTrace update = new RagTrace();
        update.setId(id);
        update.setStatus(success ? "SUCCESS" : "FAILED");
        update.setTotalDuration(durationMillis);
        update.setAnswer(answer);
        update.setPromptTokens((long) Math.max(inputTokens, 0));
        update.setCompletionTokens((long) Math.max(outputTokens, 0));
        update.setTotalTokens((long) Math.max(inputTokens, 0) + Math.max(outputTokens, 0));
        update.setErrorMessage(abbreviate(errorMessage, SUMMARY_CHARS));
        update.setFinishedAt(LocalDateTime.now());
        traceMapper.updateById(update);
    }

    /** 开始一个 Span；traceId 为空或写入失败时返回 null，调用方可以忽略。 */
    public SpanHandle startSpan(String traceId, String parentSpanId, String spanType, String inputSummary) {
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        TraceSpan span = new TraceSpan();
        span.setTraceId(traceId);
        span.setSpanId(UUID.randomUUID().toString().replace("-", ""));
        span.setParentSpanId(parentSpanId);
        span.setSpanType(abbreviate(spanType, 64));
        span.setStatus("RUNNING");
        span.setInputSummary(abbreviate(inputSummary, SUMMARY_CHARS));
        span.setCreatedAt(LocalDateTime.now());
        try {
            spanMapper.insert(span);
            return new SpanHandle(span.getId(), traceId, span.getSpanId(), System.nanoTime());
        } catch (RuntimeException exception) {
            log.warn("Trace Span 创建失败：traceId={}, type={}", traceId, spanType);
            return null;
        }
    }

    public void finishSpan(SpanHandle handle, String status, String outputSummary, String errorType,
            String errorMessage, int inputTokens, int outputTokens) {
        if (handle == null || handle.id() == null) {
            return;
        }
        TraceSpan update = new TraceSpan();
        update.setId(handle.id());
        update.setStatus(abbreviate(status, 32));
        update.setDuration((System.nanoTime() - handle.startedAtNanos()) / 1_000_000);
        update.setOutputSummary(abbreviate(outputSummary, SUMMARY_CHARS));
        update.setErrorType(abbreviate(errorType, 64));
        update.setErrorMessage(abbreviate(errorMessage, SUMMARY_CHARS));
        update.setInputTokens((long) Math.max(inputTokens, 0));
        update.setOutputTokens((long) Math.max(outputTokens, 0));
        try {
            spanMapper.updateById(update);
        } catch (RuntimeException exception) {
            log.warn("Trace Span 收尾失败：traceId={}, spanId={}", handle.traceId(), handle.spanId());
        }
    }

    public void failSpan(SpanHandle handle, String errorType, String errorMessage) {
        finishSpan(handle, "FAILED", null, errorType, errorMessage, 0, 0);
    }

    /** 一次性写入已经完成的 Span，适用于没有嵌套关系的外部节点。 */
    public void recordSpan(String traceId, String parentSpanId, String spanType, String status,
            long durationMillis, String inputSummary, String outputSummary, String errorType,
            String errorMessage, int inputTokens, int outputTokens) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        TraceSpan span = new TraceSpan();
        span.setTraceId(traceId);
        span.setSpanId(UUID.randomUUID().toString().replace("-", ""));
        span.setParentSpanId(parentSpanId);
        span.setSpanType(abbreviate(spanType, 64));
        span.setStatus(abbreviate(status, 32));
        span.setDuration(Math.max(durationMillis, 0));
        span.setInputSummary(abbreviate(inputSummary, SUMMARY_CHARS));
        span.setOutputSummary(abbreviate(outputSummary, SUMMARY_CHARS));
        span.setErrorType(abbreviate(errorType, 64));
        span.setErrorMessage(abbreviate(errorMessage, SUMMARY_CHARS));
        span.setInputTokens((long) Math.max(inputTokens, 0));
        span.setOutputTokens((long) Math.max(outputTokens, 0));
        span.setCreatedAt(LocalDateTime.now());
        try {
            spanMapper.insert(span);
        } catch (RuntimeException exception) {
            log.warn("Trace Span 写入失败：traceId={}, type={}", traceId, spanType);
        }
    }

    public List<RagTrace> list(Long ownerId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return traceMapper.selectList(new LambdaQueryWrapper<RagTrace>()
                .eq(RagTrace::getUserId, ownerId)
                .orderByDesc(RagTrace::getCreatedAt)
                .last("LIMIT " + safeLimit));
    }

    public RagTrace get(String traceId, Long ownerId) {
        RagTrace trace = traceMapper.selectOne(new LambdaQueryWrapper<RagTrace>()
                .eq(RagTrace::getTraceId, traceId)
                .eq(RagTrace::getUserId, ownerId));
        if (trace == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Trace 不存在或不可访问");
        }
        return trace;
    }

    public List<TraceSpan> spans(String traceId, Long ownerId) {
        // 先校验 Trace 归属，避免通过 traceId 读取其他用户的执行节点。
        get(traceId, ownerId);
        return spanMapper.selectList(new LambdaQueryWrapper<TraceSpan>()
                .eq(TraceSpan::getTraceId, traceId)
                .orderByAsc(TraceSpan::getId));
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...";
    }

    /** Span 句柄，用于在业务结束时补写状态、耗时、输出和 Token。 */
    public record SpanHandle(Long id, String traceId, String spanId, long startedAtNanos) {
    }
}
