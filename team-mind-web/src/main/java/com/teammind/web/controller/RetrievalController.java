package com.teammind.web.controller;

import com.teammind.common.api.ApiResponse;
import com.teammind.knowledge.api.RetrievalResult;
import com.teammind.knowledge.api.SearchStrategy;
import com.teammind.knowledge.application.KnowledgeSearchService;
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
@RequestMapping("/api/v1/retrieval")
public class RetrievalController {
    private final KnowledgeSearchService searchService;

    public RetrievalController(KnowledgeSearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/search")
    public ApiResponse<RetrievalResult> search(@Valid @RequestBody SearchRequest request) {
        return ApiResponse.success(searchService.searchWithTrace(request.query(), request.knowledgeBaseIds(),
                request.ownerId(), request.topK(), request.strategy()));
    }

    public record SearchRequest(@NotBlank @Size(max = 4000) String query,
            @NotEmpty @Size(max = 20) List<@NotNull @Positive Long> knowledgeBaseIds,
            @NotNull @Positive Long ownerId,
            @Positive Integer topK, @NotNull SearchStrategy strategy) {
    }
}
