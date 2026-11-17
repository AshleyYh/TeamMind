package com.teammind.web.controller;

import com.teammind.common.api.ApiResponse;
import com.teammind.knowledge.application.KnowledgeBaseService;
import com.teammind.knowledge.domain.KnowledgeBase;
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
@Validated
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<KnowledgeBaseResponse> create(@Valid @RequestBody KnowledgeBaseRequest request) {
        return ApiResponse.success(KnowledgeBaseResponse.from(
                service.create(request.name(), request.description(), request.ownerId())));
    }

    @GetMapping
    public ApiResponse<List<KnowledgeBaseResponse>> list(@RequestParam @Positive Long ownerId) {
        return ApiResponse.success(service.list(ownerId).stream()
                .map(KnowledgeBaseResponse::from).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> get(
            @PathVariable @Positive Long id, @RequestParam @Positive Long ownerId) {
        return ApiResponse.success(KnowledgeBaseResponse.from(service.get(id, ownerId)));
    }

    @PutMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> update(@PathVariable @Positive Long id,
            @Valid @RequestBody KnowledgeBaseRequest request) {
        return ApiResponse.success(KnowledgeBaseResponse.from(
                service.update(id, request.name(), request.description(), request.ownerId())));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable @Positive Long id,
            @RequestParam @Positive Long ownerId) {
        service.delete(id, ownerId);
        return ApiResponse.success();
    }

    public record KnowledgeBaseRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 2000) String description,
            @NotNull @Positive Long ownerId) {
    }

    /** 知识库对外模型，不暴露逻辑删除等内部字段。 */
    public record KnowledgeBaseResponse(Long id, String name, String description, Long ownerId,
            Integer status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        static KnowledgeBaseResponse from(KnowledgeBase knowledgeBase) {
            return new KnowledgeBaseResponse(knowledgeBase.getId(), knowledgeBase.getName(),
                    knowledgeBase.getDescription(), knowledgeBase.getOwnerId(), knowledgeBase.getStatus(),
                    knowledgeBase.getCreatedAt(), knowledgeBase.getUpdatedAt());
        }
    }
}
