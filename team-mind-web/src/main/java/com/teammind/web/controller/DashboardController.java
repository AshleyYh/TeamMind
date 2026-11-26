package com.teammind.web.controller;

import com.teammind.common.api.ApiResponse;
import com.teammind.web.dashboard.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 首页统计接口：知识库、文档、会话、记忆、Trace、评测数量和最近执行。 */
@Tag(name = "首页统计", description = "查询当前用户的资源数量、失败执行和最近 Trace，用于控制台概览。")
@Validated
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Operation(summary = "查询首页统计", description = "统计知识库、文档、会话、记忆、Trace 和评测运行数量，并返回最近 5 次执行。")
    @GetMapping("/stats")
    public ApiResponse<DashboardService.DashboardStats> stats(
            @Parameter(description = "开发期归属用户 ID") @RequestParam @Positive Long ownerId) {
        return ApiResponse.success(dashboardService.stats(ownerId));
    }
}
