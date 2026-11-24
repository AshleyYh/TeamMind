package com.teammind.web.controller;

import com.teammind.common.api.ApiResponse;
import com.teammind.knowledge.application.DocumentService;
import com.teammind.knowledge.domain.Document;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "文档管理", description = "文档上传、解析分块、异步向量化、状态查询、重处理与删除。")
@Validated
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @Operation(summary = "上传文档", description = """
            上传 PDF、DOCX、TXT 或 Markdown 文件，单文件默认不超过 20 MB。
            接口只保存文件并登记异步处理任务，立即返回 PENDING 状态，随后可通过状态接口轮询。
            同名文档再次上传会创建新版本；旧版本分块和向量在新版本处理成功后不再参与检索。
            文档正在处理时上传同名文件会返回 42201；超过大小限制返回 41301。
            """)
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentResponse> upload(
            @Parameter(description = "目标知识库 ID，必须属于当前 ownerId") @RequestParam @Positive Long knowledgeBaseId,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId,
            @Parameter(description = "待上传文件（PDF/DOCX/TXT/MD，默认上限 20 MB）") @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(DocumentResponse.from(service.upload(knowledgeBaseId, ownerId, file)));
    }

    @Operation(summary = "查询文档列表", description = "按知识库和归属用户查询未删除的文档，不返回服务器文件路径。")
    @GetMapping
    public ApiResponse<List<DocumentResponse>> list(
            @Parameter(description = "知识库 ID") @RequestParam @Positive Long knowledgeBaseId,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        return ApiResponse.success(service.list(knowledgeBaseId, ownerId).stream()
                .map(DocumentResponse::from).toList());
    }

    @Operation(summary = "查询文档详情", description = "返回文档元数据、当前版本和处理状态；不属于当前用户时返回 40401。")
    @GetMapping("/{id}")
    public ApiResponse<DocumentResponse> get(
            @Parameter(description = "文档 ID") @PathVariable @Positive Long id,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        return ApiResponse.success(DocumentResponse.from(service.get(id, ownerId)));
    }

    @Operation(summary = "查询文档处理状态", description = """
            轻量状态接口，适合上传后轮询。状态流转为 PENDING → PROCESSING → COMPLETED/FAILED。
            失败时 failureReason 返回面向业务的原因；未知异常只记录服务端日志。
            """)
    @GetMapping("/{id}/status")
    public ApiResponse<DocumentStatus> status(
            @Parameter(description = "文档 ID") @PathVariable @Positive Long id,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        Document document = service.get(id, ownerId);
        return ApiResponse.success(new DocumentStatus(document.getId(), document.getCurrentVersion(),
                document.getProcessingStatus(), document.getFailureReason(), document.getUpdatedAt()));
    }

    @Operation(summary = "重新处理文档", description = """
            对当前版本重新解析、分块、生成 Embedding 与 BM25 检索词元，并清理旧分块和向量。
            Phase 2 已入库的旧文档需要调用本接口补齐检索词元后才能参与 BM25 召回。
            """)
    @PostMapping("/{id}/reprocess")
    public ApiResponse<DocumentResponse> reprocess(
            @Parameter(description = "文档 ID") @PathVariable @Positive Long id,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        return ApiResponse.success(DocumentResponse.from(service.reprocess(id, ownerId)));
    }

    @Operation(summary = "删除文档", description = "逻辑删除文档并同步清理数据库分块和 Qdrant 向量，避免残留证据被检索到。")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @Parameter(description = "文档 ID") @PathVariable @Positive Long id,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        service.delete(id, ownerId);
        return ApiResponse.success();
    }

    /** 文档对外模型，刻意隐藏服务器存储路径和逻辑删除字段。 */
    @Schema(name = "DocumentResponse", description = "文档信息，不包含服务器存储路径")
    public record DocumentResponse(
            @Schema(description = "文档 ID", example = "1001") Long id,
            @Schema(description = "所属知识库 ID", example = "1") Long knowledgeBaseId,
            @Schema(description = "原始文件名", example = "接口设计文档.pdf") String documentName,
            @Schema(description = "文件类型（小写扩展名）", example = "pdf") String fileType,
            @Schema(description = "当前版本号，从 1 开始；同名重新上传会递增", example = "1") Integer currentVersion,
            @Schema(description = "处理状态：PENDING / PROCESSING / COMPLETED / FAILED", example = "COMPLETED")
            String processingStatus,
            @Schema(description = "失败原因，仅 FAILED 时有值") String failureReason,
            @Schema(description = "创建时间") LocalDateTime createdAt,
            @Schema(description = "最后更新时间") LocalDateTime updatedAt) {
        static DocumentResponse from(Document document) {
            return new DocumentResponse(document.getId(), document.getKnowledgeBaseId(),
                    document.getDocumentName(), document.getFileType(), document.getCurrentVersion(),
                    document.getProcessingStatus(), document.getFailureReason(),
                    document.getCreatedAt(), document.getUpdatedAt());
        }
    }

    @Schema(name = "DocumentStatus", description = "文档处理状态")
    public record DocumentStatus(
            @Schema(description = "文档 ID", example = "1001") Long id,
            @Schema(description = "当前版本号", example = "1") Integer version,
            @Schema(description = "处理状态：PENDING / PROCESSING / COMPLETED / FAILED", example = "PROCESSING")
            String status,
            @Schema(description = "失败原因，仅 FAILED 时有值") String failureReason,
            @Schema(description = "最后更新时间") LocalDateTime updatedAt) {
    }
}
