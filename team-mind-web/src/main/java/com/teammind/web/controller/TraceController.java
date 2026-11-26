package com.teammind.web.controller;

import com.teammind.common.api.ApiResponse;
import com.teammind.observability.application.EvidenceService;
import com.teammind.observability.application.RagTraceService;
import com.teammind.observability.domain.Evidence;
import com.teammind.observability.domain.RagTrace;
import com.teammind.observability.domain.TraceSpan;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Trace 查询接口：按 traceId 查看一次请求的完整执行节点、耗时、Token 和 Evidence。
 */
@Tag(name = "执行追踪", description = "查询 Agent/RAG 请求的 Trace、Span、耗时、Token、失败节点和证据。")
@Validated
@RestController
@RequestMapping("/api/v1/traces")
public class TraceController {
    private final RagTraceService traceService;
    private final EvidenceService evidenceService;

    public TraceController(RagTraceService traceService, EvidenceService evidenceService) {
        this.traceService = traceService;
        this.evidenceService = evidenceService;
    }

    @Operation(summary = "查询 Trace 列表", description = "按归属用户返回最近请求，默认 20 条，最多 100 条。")
    @GetMapping
    public ApiResponse<List<TraceSummaryResponse>> list(
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId,
            @Parameter(description = "返回条数") @RequestParam(defaultValue = "20")
            @Min(1) @Max(100) int limit) {
        return ApiResponse.success(traceService.list(ownerId, limit).stream()
                .map(TraceSummaryResponse::from).toList());
    }

    @Operation(summary = "查询 Trace 详情", description = "返回 Trace 主记录和全部 Span；不存在或不属于当前用户时返回 40401。")
    @GetMapping("/{traceId}")
    public ApiResponse<TraceDetailResponse> detail(
            @Parameter(description = "32 位十六进制 traceId") @PathVariable
            @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String traceId,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        RagTrace trace = traceService.get(traceId, ownerId);
        return ApiResponse.success(new TraceDetailResponse(trace, traceService.spans(traceId, ownerId)));
    }

    @Operation(summary = "查询执行节点", description = "返回平铺 Span 列表，parentSpanId 可用于构建树形结构；同时包含失败节点、耗时和 Token。")
    @GetMapping("/{traceId}/spans")
    public ApiResponse<List<TraceSpan>> spans(
            @Parameter(description = "32 位十六进制 traceId") @PathVariable
            @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String traceId,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        return ApiResponse.success(traceService.spans(traceId, ownerId));
    }

    @Operation(summary = "查询 Trace 证据", description = "返回该 Trace 关联的 Evidence 快照；先校验 Trace 归属。")
    @GetMapping("/{traceId}/evidences")
    public ApiResponse<List<Evidence>> evidences(
            @Parameter(description = "32 位十六进制 traceId") @PathVariable
            @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String traceId,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        traceService.get(traceId, ownerId);
        return ApiResponse.success(evidenceService.list(traceId, ownerId));
    }

    /** Trace 列表项，省略完整回答以减少列表响应体积。 */
    public record TraceSummaryResponse(
            Long id,
            String traceId,
            Long userId,
            String conversationId,
            String status,
            Long totalDuration,
            Long totalTokens,
            Long promptTokens,
            Long completionTokens,
            String strategy,
            String query,
            String errorMessage,
            LocalDateTime createdAt,
            LocalDateTime finishedAt) {

        static TraceSummaryResponse from(RagTrace trace) {
            return new TraceSummaryResponse(trace.getId(), trace.getTraceId(), trace.getUserId(),
                    trace.getConversationId(), trace.getStatus(), trace.getTotalDuration(),
                    trace.getTotalTokens(), trace.getPromptTokens(), trace.getCompletionTokens(),
                    trace.getStrategy(), trace.getQuery(), trace.getErrorMessage(),
                    trace.getCreatedAt(), trace.getFinishedAt());
        }
    }

    /** Trace 详情和节点。 */
    public record TraceDetailResponse(RagTrace trace, List<TraceSpan> spans) {
    }
}
