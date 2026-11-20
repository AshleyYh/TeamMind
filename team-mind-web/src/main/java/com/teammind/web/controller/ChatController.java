package com.teammind.web.controller;

import com.teammind.ai.rag.RagAnswerService;
import com.teammind.common.api.ApiResponse;
import com.teammind.knowledge.api.SearchStrategy;
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

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final RagAnswerService ragAnswerService;

    public ChatController(RagAnswerService ragAnswerService) {
        this.ragAnswerService = ragAnswerService;
    }

    @PostMapping("/completions")
    public ApiResponse<RagAnswerService.RagAnswer> complete(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.success(ragAnswerService.answer(request.message(), request.knowledgeBaseIds(),
                request.ownerId(), request.topK(), request.strategy()));
    }

    public record ChatRequest(
            @NotBlank @Size(max = 4000) String message,
            @NotEmpty @Size(max = 20) List<@NotNull @Positive Long> knowledgeBaseIds,
            @NotNull @Positive Long ownerId,
            @Positive Integer topK,
            SearchStrategy strategy) {
    }
}
