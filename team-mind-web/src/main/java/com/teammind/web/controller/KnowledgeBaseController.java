package com.teammind.web.controller;

import com.teammind.common.api.ApiResponse;
import com.teammind.knowledge.application.KnowledgeBaseService;
import com.teammind.knowledge.domain.KnowledgeBase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

/** 知识库管理接口。当前阶段尚未接入登录，ownerId 仅用于开发期的显式归属校验。 */
@Tag(name = "知识库管理", description = "知识库的创建、查询、修改和删除。ownerId 为开发期归属参数，不是身份认证。")
@Validated
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @Operation(summary = "创建知识库", description = "同一用户下知识库名称不能重复，重复时返回 40901。")
    @PostMapping
    public ApiResponse<KnowledgeBaseResponse> create(@Valid @RequestBody KnowledgeBaseRequest request) {
        return ApiResponse.success(KnowledgeBaseResponse.from(
                service.create(request.name(), request.description(), request.ownerId())));
    }

    @Operation(summary = "查询知识库列表", description = "按归属用户查询未删除的知识库，按创建时间倒序返回。")
    @GetMapping
    public ApiResponse<List<KnowledgeBaseResponse>> list(
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        return ApiResponse.success(service.list(ownerId).stream()
                .map(KnowledgeBaseResponse::from).toList());
    }

    @Operation(summary = "查询知识库详情", description = "知识库不存在或不属于当前用户时统一返回 40401，避免暴露存在性。")
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> get(
            @Parameter(description = "知识库 ID") @PathVariable @Positive Long id,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        return ApiResponse.success(KnowledgeBaseResponse.from(service.get(id, ownerId)));
    }

    @Operation(summary = "修改知识库", description = "修改名称和描述；名称冲突时返回 40901，归属用户不可修改。")
    @PutMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> update(
            @Parameter(description = "知识库 ID") @PathVariable @Positive Long id,
            @Valid @RequestBody KnowledgeBaseRequest request) {
        return ApiResponse.success(KnowledgeBaseResponse.from(
                service.update(id, request.name(), request.description(), request.ownerId())));
    }

    @Operation(summary = "删除知识库", description = "逻辑删除知识库；知识库中仍有文档时拒绝删除并返回 42201，需先删除文档。")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @Parameter(description = "知识库 ID") @PathVariable @Positive Long id,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        service.delete(id, ownerId);
        return ApiResponse.success();
    }

    /** 知识库请求参数。 */
    @Schema(name = "KnowledgeBaseRequest", description = "知识库创建或修改参数")
    public record KnowledgeBaseRequest(
            @Schema(description = "知识库名称，同一用户下唯一", example = "研发知识库", maxLength = 128)
            @NotBlank @Size(max = 128) String name,

            @Schema(description = "知识库描述", example = "存放接口文档、设计文档和故障记录", maxLength = 2000)
            @Size(max = 2000) String description,

            @Schema(description = "开发期归属用户 ID；当前尚未接入登录，不代表安全认证", example = "1")
            @NotNull @Positive Long ownerId) {
    }

    /** 知识库对外模型，不暴露逻辑删除等内部字段。 */
    @Schema(name = "KnowledgeBaseResponse", description = "知识库信息，不包含逻辑删除等内部字段")
    public record KnowledgeBaseResponse(
            @Schema(description = "知识库 ID", example = "1") Long id,
            @Schema(description = "知识库名称", example = "研发知识库") String name,
            @Schema(description = "知识库描述") String description,
            @Schema(description = "归属用户 ID", example = "1") Long ownerId,
            @Schema(description = "状态：1 正常", example = "1") Integer status,
            @Schema(description = "创建时间") LocalDateTime createdAt,
            @Schema(description = "最后更新时间") LocalDateTime updatedAt) {
        static KnowledgeBaseResponse from(KnowledgeBase knowledgeBase) {
            return new KnowledgeBaseResponse(knowledgeBase.getId(), knowledgeBase.getName(),
                    knowledgeBase.getDescription(), knowledgeBase.getOwnerId(), knowledgeBase.getStatus(),
                    knowledgeBase.getCreatedAt(), knowledgeBase.getUpdatedAt());
        }
    }
}
