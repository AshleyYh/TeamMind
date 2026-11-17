package com.teammind.web.controller;

import com.teammind.common.api.ApiResponse;
import com.teammind.knowledge.application.DocumentService;
import com.teammind.knowledge.domain.Document;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/** 文档上传、查询、重处理和删除接口。文件实体路径不会通过接口返回。 */
@Validated
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentResponse> upload(@RequestParam @Positive Long knowledgeBaseId,
            @RequestParam @Positive Long ownerId, @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(DocumentResponse.from(service.upload(knowledgeBaseId, ownerId, file)));
    }

    @GetMapping
    public ApiResponse<List<DocumentResponse>> list(@RequestParam @Positive Long knowledgeBaseId,
            @RequestParam @Positive Long ownerId) {
        return ApiResponse.success(service.list(knowledgeBaseId, ownerId).stream()
                .map(DocumentResponse::from).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentResponse> get(
            @PathVariable @Positive Long id, @RequestParam @Positive Long ownerId) {
        return ApiResponse.success(DocumentResponse.from(service.get(id, ownerId)));
    }

    @GetMapping("/{id}/status")
    public ApiResponse<DocumentStatus> status(
            @PathVariable @Positive Long id, @RequestParam @Positive Long ownerId) {
        Document document = service.get(id, ownerId);
        return ApiResponse.success(new DocumentStatus(document.getId(), document.getCurrentVersion(),
                document.getProcessingStatus(), document.getFailureReason(), document.getUpdatedAt()));
    }

    @PostMapping("/{id}/reprocess")
    public ApiResponse<DocumentResponse> reprocess(
            @PathVariable @Positive Long id, @RequestParam @Positive Long ownerId) {
        return ApiResponse.success(DocumentResponse.from(service.reprocess(id, ownerId)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable @Positive Long id,
            @RequestParam @Positive Long ownerId) {
        service.delete(id, ownerId);
        return ApiResponse.success();
    }

    /** 文档对外模型，刻意隐藏服务器存储路径和逻辑删除字段。 */
    public record DocumentResponse(Long id, Long knowledgeBaseId, String documentName, String fileType,
            Integer currentVersion, String processingStatus, String failureReason,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
        static DocumentResponse from(Document document) {
            return new DocumentResponse(document.getId(), document.getKnowledgeBaseId(),
                    document.getDocumentName(), document.getFileType(), document.getCurrentVersion(),
                    document.getProcessingStatus(), document.getFailureReason(),
                    document.getCreatedAt(), document.getUpdatedAt());
        }
    }

    public record DocumentStatus(Long id, Integer version, String status, String failureReason,
            LocalDateTime updatedAt) {
    }
}
