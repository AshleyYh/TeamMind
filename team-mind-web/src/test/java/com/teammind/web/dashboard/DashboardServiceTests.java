package com.teammind.web.dashboard;

import com.teammind.observability.application.RagTraceService;
import com.teammind.observability.domain.RagTrace;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardServiceTests {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final RagTraceService traceService = mock(RagTraceService.class);
    private final DashboardService service = new DashboardService(jdbcTemplate, traceService);

    @Test
    void 汇总首页数量和最近执行() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(1L)))
                .thenReturn(1L, 2L, 3L, 4L, 5L, 6L, 0L);
        RagTrace trace = new RagTrace();
        trace.setTraceId("0123456789abcdef0123456789abcdef");
        when(traceService.list(1L, 5)).thenReturn(List.of(trace));

        var stats = service.stats(1L);

        assertThat(stats.knowledgeBaseCount()).isEqualTo(1);
        assertThat(stats.documentCount()).isEqualTo(2);
        assertThat(stats.conversationCount()).isEqualTo(3);
        assertThat(stats.memoryCount()).isEqualTo(4);
        assertThat(stats.traceCount()).isEqualTo(5);
        assertThat(stats.evaluationRunCount()).isEqualTo(6);
        assertThat(stats.failedTraceCount()).isZero();
        assertThat(stats.recentTraces()).containsExactly(trace);
    }
}
