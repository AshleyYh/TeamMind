package com.teammind.web.controller;

import com.teammind.common.api.ApiResponse;
import com.teammind.observability.application.EvidenceService;
import com.teammind.observability.domain.Evidence;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Evidence Board 查询接口：按 traceId 查看一次检索或问答产生的证据快照。 */
@Tag(name = "证据", description = "按 traceId 查询 Evidence Board，查看检索证据、各阶段排名分数和引用状态。")
@Validated
@RestController
@RequestMapping("/api/v1/evidence")
public class EvidenceController {
    private final EvidenceService evidenceService;

    public EvidenceController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @Operation(summary = "查询证据快照", description = """
            按问答或检索接口返回的 32 位十六进制 traceId 查询该次请求的 Evidence Board。
            每条证据包含：chunkId、documentId、documentName、documentVersion、pageNumber、content，
            retrievalType（检索策略），denseRank、bm25Rank、denseScore、bm25Score、rrfScore、rerankScore，
            reranked（是否经过重排）、cited（回答中是否实际引用了该证据）以及 createdAt。
            证据保存的是检索时的原文快照，文档之后被重处理或删除也不会影响历史查询。
            traceId 不存在或不属于当前用户时返回空列表。
            """)
    @GetMapping("/{traceId}")
    public ApiResponse<List<Evidence>> list(
            @Parameter(description = "32 位十六进制 traceId，由问答或检索接口返回", example = "9f2c4e1a7b3d4c5e8f6a1b2c3d4e5f60")
            @PathVariable @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String traceId,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @NotNull @Positive Long ownerId) {
        return ApiResponse.success(evidenceService.list(traceId, ownerId));
    }
}
