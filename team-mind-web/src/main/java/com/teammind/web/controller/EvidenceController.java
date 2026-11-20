package com.teammind.web.controller;

import com.teammind.common.api.ApiResponse;
import com.teammind.observability.application.EvidenceService;
import com.teammind.observability.domain.Evidence;
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

@Validated
@RestController
@RequestMapping("/api/v1/evidence")
public class EvidenceController {
    private final EvidenceService evidenceService;

    public EvidenceController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @GetMapping("/{traceId}")
    public ApiResponse<List<Evidence>> list(
            @PathVariable @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String traceId,
            @RequestParam @NotNull @Positive Long ownerId) {
        return ApiResponse.success(evidenceService.list(traceId, ownerId));
    }
}
