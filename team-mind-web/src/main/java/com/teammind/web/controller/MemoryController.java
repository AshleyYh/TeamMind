package com.teammind.web.controller;

import com.teammind.common.api.ApiResponse;
import com.teammind.memory.application.MemoryService;
import com.teammind.memory.domain.Memory;
import com.teammind.memory.domain.MemoryStatus;
import com.teammind.memory.domain.MemoryType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 长期记忆接口。新记忆默认是 DRAFT，必须显式确认后才参与 Agent 检索；
 * 更新递增版本，失效和删除保留来源会话/Trace 用于追溯。
 */
@Tag(name = "长期记忆", description = "长期记忆的创建、确认、修改、失效、删除和检索。ownerId 为开发期归属参数。")
@Validated
@RestController
@RequestMapping("/api/v1/memories")
public class MemoryController {
    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Operation(summary = "查询长期记忆列表", description = "按归属用户查询未删除记忆，可按状态和类型过滤，默认按更新时间倒序。")
    @GetMapping
    public ApiResponse<List<MemoryResponse>> list(
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId,
            @Parameter(description = "记忆状态：DRAFT、ACTIVE、INVALID") @RequestParam(required = false)
            MemoryStatus status,
            @Parameter(description = "记忆类型") @RequestParam(required = false) MemoryType type,
            @Parameter(description = "返回条数，最大 100") @RequestParam(defaultValue = "50")
            @Min(1) @Max(100) int limit) {
        return ApiResponse.success(memoryService.list(ownerId, status, type, limit).stream()
                .map(MemoryResponse::from).toList());
    }

    @Operation(summary = "检索已确认记忆", description = "只检索 ACTIVE 记忆，供调试和前端展示；Agent 通过 historyMemorySearch 工具使用同一服务。")
    @GetMapping("/search")
    public ApiResponse<List<MemoryResponse>> search(
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId,
            @Parameter(description = "检索关键词") @RequestParam @NotBlank @Size(max = 1000) String query,
            @Parameter(description = "返回条数，最大 50") @RequestParam(defaultValue = "5")
            @Min(1) @Max(50) int limit) {
        return ApiResponse.success(memoryService.search(ownerId, query, limit).stream()
                .map(MemoryResponse::from).toList());
    }

    @Operation(summary = "查询记忆详情", description = "不存在或不属于当前用户时统一返回 40401。")
    @GetMapping("/{id}")
    public ApiResponse<MemoryResponse> get(
            @Parameter(description = "记忆 ID") @PathVariable @Positive Long id,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        return ApiResponse.success(MemoryResponse.from(memoryService.get(id, ownerId)));
    }

    @Operation(summary = "新增记忆草稿", description = "新增后状态为 DRAFT，需要确认接口后才能被 Agent 检索。相同类型和内容的未失效记忆会幂等复用。")
    @PostMapping
    public ApiResponse<MemoryResponse> create(@Valid @RequestBody MemoryCreateRequest request) {
        return ApiResponse.success(MemoryResponse.from(memoryService.createDraft(request.ownerId(),
                request.memoryType(), request.title(), request.content(), request.summary(),
                request.sourceConversationId(), request.sourceTraceId(), request.confidence())));
    }

    @Operation(summary = "修改记忆", description = "修改内容、标题、类型和摘要，版本号递增；已失效记忆不能修改。")
    @PutMapping("/{id}")
    public ApiResponse<MemoryResponse> update(
            @Parameter(description = "记忆 ID") @PathVariable @Positive Long id,
            @Valid @RequestBody MemoryUpdateRequest request) {
        return ApiResponse.success(MemoryResponse.from(memoryService.update(id, request.ownerId(),
                request.memoryType(), request.title(), request.content(), request.summary())));
    }

    @Operation(summary = "确认记忆", description = "把 DRAFT 记忆变为 ACTIVE，确认后才会被 Agent 检索。")
    @PostMapping("/{id}/confirm")
    public ApiResponse<MemoryResponse> confirm(
            @Parameter(description = "记忆 ID") @PathVariable @Positive Long id,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        return ApiResponse.success(MemoryResponse.from(memoryService.confirm(id, ownerId)));
    }

    @Operation(summary = "使记忆失效", description = "把记忆标记为 INVALID，立即停止参与检索，但保留来源数据。")
    @PostMapping("/{id}/invalidate")
    public ApiResponse<MemoryResponse> invalidate(
            @Parameter(description = "记忆 ID") @PathVariable @Positive Long id,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        return ApiResponse.success(MemoryResponse.from(memoryService.invalidate(id, ownerId)));
    }

    @Operation(summary = "删除记忆", description = "逻辑删除记忆并标记为 INVALID；来源会话和 Trace 仍可用于历史追溯。")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @Parameter(description = "记忆 ID") @PathVariable @Positive Long id,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        memoryService.delete(id, ownerId);
        return ApiResponse.success();
    }

    /** 新增记忆参数。 */
    public record MemoryCreateRequest(
            @Schema(description = "开发期归属用户 ID", example = "1")
            @NotNull @Positive Long ownerId,

            @Schema(description = "记忆类型", example = "FACT")
            @NotNull MemoryType memoryType,

            @Schema(description = "简短标题，不传时从内容截取", maxLength = 255)
            @Size(max = 255) String title,

            @Schema(description = "记忆内容", example = "订单模块使用 HikariCP，最大连接数为 30", maxLength = 4000)
            @NotBlank @Size(max = 4000) String content,

            @Schema(description = "摘要，不传时从内容截取", maxLength = 2000)
            @Size(max = 2000) String summary,

            @Schema(description = "来源会话 ID", maxLength = 64)
            @Size(max = 64) String sourceConversationId,

            @Schema(description = "来源 Trace ID", maxLength = 64)
            @Size(max = 64) String sourceTraceId,

            @Schema(description = "置信度 0 到 1", example = "0.9")
            @DecimalMin("0.0") @DecimalMax("1.0") Double confidence) {
    }

    /** 修改记忆参数，状态通过确认/失效接口单独变更。 */
    public record MemoryUpdateRequest(
            @Schema(description = "开发期归属用户 ID", example = "1")
            @NotNull @Positive Long ownerId,

            @Schema(description = "记忆类型", example = "FACT")
            MemoryType memoryType,

            @Schema(description = "简短标题", maxLength = 255)
            @Size(max = 255) String title,

            @Schema(description = "记忆内容", maxLength = 4000)
            @NotBlank @Size(max = 4000) String content,

            @Schema(description = "摘要", maxLength = 2000)
            @Size(max = 2000) String summary) {
    }

    /** 对外记忆模型，不暴露逻辑删除字段。 */
    public record MemoryResponse(
            Long id,
            Long ownerId,
            MemoryType memoryType,
            String title,
            String content,
            String summary,
            String sourceConversationId,
            String sourceTraceId,
            Integer version,
            MemoryStatus status,
            Double confidence,
            LocalDateTime lastUsedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        static MemoryResponse from(Memory memory) {
            return new MemoryResponse(memory.getId(), memory.getUserId(), memory.getMemoryType(),
                    memory.getTitle(), memory.getContent(), memory.getSummary(),
                    memory.getSourceConversationId(), memory.getSourceTraceId(), memory.getVersion(),
                    memory.getStatus(), memory.getConfidence(), memory.getLastUsedAt(),
                    memory.getCreatedAt(), memory.getUpdatedAt());
        }
    }
}
