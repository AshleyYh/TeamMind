package com.teammind.web.controller;

import com.teammind.web.dashboard.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardControllerTests {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @Test
    void 首页统计返回数量和最近执行() throws Exception {
        when(dashboardService.stats(1L)).thenReturn(new DashboardService.DashboardStats(
                2, 5, 3, 4, 10, 2, 1, List.of()));

        mockMvc.perform(get("/api/v1/dashboard/stats").param("ownerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.knowledgeBaseCount").value(2))
                .andExpect(jsonPath("$.data.documentCount").value(5))
                .andExpect(jsonPath("$.data.failedTraceCount").value(1));
    }

    @Test
    void 非法归属返回参数错误() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/stats").param("ownerId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }
}
