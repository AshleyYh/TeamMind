package com.teammind.web.controller;

import com.teammind.ai.agent.memory.MemoryExtractionService;
import com.teammind.common.api.ApiResponse;
import com.teammind.memory.application.ConversationService;
import com.teammind.memory.domain.Conversation;
import com.teammind.memory.domain.ConversationSummary;
import com.teammind.memory.domain.StoredMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话接口。消息正文保存在 Redis，数据库只保存会话归属和标题；
 * 删除会话会清理 Redis，但长期记忆的来源字段仍保留用于追溯。
 */
@Tag(name = "会话记忆", description = "查询会话、读取短期消息、删除会话以及从会话提取长期记忆候选。")
@Validated
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService conversationService;
    private final MemoryExtractionService memoryExtractionService;

    public ConversationController(ConversationService conversationService,
            MemoryExtractionService memoryExtractionService) {
        this.conversationService = conversationService;
        this.memoryExtractionService = memoryExtractionService;
    }

    @Operation(summary = "查询会话列表", description = "按归属用户查询未删除会话，按最后更新时间倒序返回。")
    @GetMapping
    public ApiResponse<List<ConversationResponse>> list(
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        return ApiResponse.success(conversationService.list(ownerId).stream()
                .map(ConversationResponse::from).toList());
    }

    @Operation(summary = "查询会话消息", description = "返回增量摘要和最近消息；消息按时间正序排列。")
    @GetMapping("/{conversationId}/messages")
    public ApiResponse<ConversationMessagesResponse> messages(
            @Parameter(description = "会话标识") @PathVariable
            @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String conversationId,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId,
            @Parameter(description = "最近消息条数，最大 200") @RequestParam(defaultValue = "50")
            @Min(1) @Max(200) int limit) {
        Conversation conversation = conversationService.get(conversationId, ownerId);
        ConversationSummary summary = conversationService.getSummary(conversationId, ownerId);
        List<StoredMessage> messages = conversationService.loadMessages(conversationId, ownerId, limit);
        return ApiResponse.success(new ConversationMessagesResponse(conversation.getConversationId(),
                conversation.getTitle(), summary, messages));
    }

    @Operation(summary = "删除会话", description = "逻辑删除会话元数据并清理 Redis 消息和摘要；已确认长期记忆不受影响。")
    @DeleteMapping("/{conversationId}")
    public ApiResponse<Void> delete(
            @Parameter(description = "会话标识") @PathVariable
            @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String conversationId,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        conversationService.delete(conversationId, ownerId);
        return ApiResponse.success();
    }

    @Operation(summary = "从会话提取长期记忆", description = """
            调用模型从会话中提取稳定事实、偏好、决策或经验，保存为 DRAFT 候选并返回。
            候选不会自动生效，需要调用长期记忆确认接口后才会被 Agent 检索。
            密码、密钥、令牌等敏感信息不会被提取。
            """)
    @PostMapping("/{conversationId}/memories/extract")
    public ApiResponse<MemoryExtractionResponse> extractMemories(
            @Parameter(description = "会话标识") @PathVariable
            @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String conversationId,
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        var result = memoryExtractionService.extract(conversationId, ownerId);
        return ApiResponse.success(new MemoryExtractionResponse(
                result.memories().stream().map(MemoryController.MemoryResponse::from).toList(),
                result.warnings()));
    }

    /** 会话列表项。 */
    public record ConversationResponse(String conversationId, String title,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
        static ConversationResponse from(Conversation conversation) {
            return new ConversationResponse(conversation.getConversationId(), conversation.getTitle(),
                    conversation.getCreatedAt(), conversation.getUpdatedAt());
        }
    }

    /** 会话消息响应。 */
    public record ConversationMessagesResponse(String conversationId, String title,
            ConversationSummary summary, List<StoredMessage> messages) {
    }

    /** 记忆提取响应，memories 均为 DRAFT 候选。 */
    public record MemoryExtractionResponse(List<MemoryController.MemoryResponse> memories,
            List<String> warnings) {
    }
}
