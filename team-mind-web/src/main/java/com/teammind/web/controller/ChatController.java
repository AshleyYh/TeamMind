package com.teammind.web.controller;

import com.teammind.ai.rag.RagAnswerService;
import com.teammind.common.api.ApiResponse;
import com.teammind.knowledge.api.SearchStrategy;
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

/** 基于知识库的 RAG 问答接口：检索、重排、引用校验与回答生成。 */
@Tag(name = "问答", description = "基于知识库检索证据并生成带真实引用的回答，默认使用混合检索加重排。")
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final RagAnswerService ragAnswerService;

    public ChatController(RagAnswerService ragAnswerService) {
        this.ragAnswerService = ragAnswerService;
    }

    @Operation(summary = "知识库问答", description = """
            在指定知识库中检索与问题相关的证据，再调用对话模型生成回答。
            默认策略为 HYBRID_RERANK（Dense + BM25 并行召回、RRF 融合、Reranker 重排）。
            没有检索到证据时不会调用模型，直接返回“没有找到足够依据”的提示。
            响应中的 citations 全部来自数据库中的真实分块，包含文档、版本、页码、原文以及
            Dense/BM25 排名、RRF 分数和重排分数；traceId 可用于查询 Evidence。
            模型返回越界引用编号时，服务端会拒绝本次回答并返回 50301。
            """)
    @PostMapping("/completions")
    public ApiResponse<RagAnswerService.RagAnswer> complete(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.success(ragAnswerService.answer(request.message(), request.knowledgeBaseIds(),
                request.ownerId(), request.topK(), request.strategy()));
    }

    /** 问答请求参数。 */
    public record ChatRequest(
            @Schema(description = "用户问题", example = "订单模块的最大连接数是多少？", maxLength = 4000)
            @NotBlank @Size(max = 4000) String message,

            @Schema(description = "参与检索的知识库 ID 列表，最多 20 个", example = "[1]")
            @NotEmpty @Size(max = 20) List<@NotNull @Positive Long> knowledgeBaseIds,

            @Schema(description = "开发期归属用户 ID；当前尚未接入登录，不代表安全认证", example = "1")
            @NotNull @Positive Long ownerId,

            @Schema(description = "返回的证据条数；不传时使用服务端配置 RAG_TOP_K", example = "5")
            @Positive Integer topK,

            @Schema(description = "检索策略；不传时使用服务端配置 RAG_STRATEGY，默认 HYBRID_RERANK",
                    example = "HYBRID_RERANK")
            SearchStrategy strategy) {
    }
}
