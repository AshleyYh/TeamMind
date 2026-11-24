package com.teammind.web.controller;

import com.teammind.common.api.ApiResponse;
import com.teammind.knowledge.api.RetrievalResult;
import com.teammind.knowledge.api.SearchStrategy;
import com.teammind.knowledge.application.KnowledgeSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 独立策略检索接口，不调用对话模型，用于比较不同召回策略的效果。 */
@Tag(name = "检索", description = "按指定策略执行独立检索，返回证据分块及 Dense/BM25/RRF/重排各阶段排名与分数。")
@RestController
@RequestMapping("/api/v1/retrieval")
public class RetrievalController {
    private final KnowledgeSearchService searchService;

    public RetrievalController(KnowledgeSearchService searchService) {
        this.searchService = searchService;
    }

    @Operation(summary = "策略检索", description = """
            在指定知识库中执行一次检索，不生成回答，也不消耗对话模型调用。
            支持四种策略：
            DENSE（仅向量检索）、BM25（仅关键词检索）、HYBRID（两路并行 + RRF 融合）、
            HYBRID_RERANK（融合后再调用 Reranker 重排）。
            返回的每个分块包含 documentName、documentVersion、pageNumber、content，
            以及 denseRank、bm25Rank、denseScore、bm25Score、rrfScore、rerankScore；
            非混合策略不写 RRF 分数，非重排策略不写 rerank 分数。
            本次检索同样会写入 Evidence，traceId 可用于查询证据快照。
            """)
    @PostMapping("/search")
    public ApiResponse<RetrievalResult> search(@Valid @RequestBody SearchRequest request) {
        return ApiResponse.success(searchService.searchWithTrace(request.query(), request.knowledgeBaseIds(),
                request.ownerId(), request.topK(), request.strategy()));
    }

    /** 检索请求参数。 */
    @Schema(name = "RetrievalSearchRequest", description = "策略检索请求")
    public record SearchRequest(
            @Schema(description = "检索问题或关键词", example = "HikariCP 最大连接数", maxLength = 4000)
            @NotBlank @Size(max = 4000) String query,

            @Schema(description = "参与检索的知识库 ID 列表，最多 20 个", example = "[1]")
            @NotEmpty @Size(max = 20) List<@NotNull @Positive Long> knowledgeBaseIds,

            @Schema(description = "开发期归属用户 ID；当前尚未接入登录，不代表安全认证", example = "1")
            @NotNull @Positive Long ownerId,

            @Schema(description = "返回的证据条数；不传时使用服务端配置 RAG_TOP_K", example = "5")
            @Positive Integer topK,

            @Schema(description = "检索策略，必填", example = "BM25")
            @NotNull SearchStrategy strategy) {
    }
}
