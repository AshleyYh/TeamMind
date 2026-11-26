package com.teammind.evaluation.application;

import com.teammind.evaluation.dataset.DatasetLoader;
import com.teammind.evaluation.dataset.MemoryDataset;
import com.teammind.evaluation.domain.EvaluationRun;
import com.teammind.evaluation.report.EvaluationReport;
import com.teammind.evaluation.report.EvaluationReportRenderer;
import com.teammind.memory.application.MemoryService;
import com.teammind.memory.domain.Memory;
import com.teammind.memory.domain.MemoryStatus;
import com.teammind.memory.domain.MemoryType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryEvaluationServiceTests {
    private final DatasetLoader loader = mock(DatasetLoader.class);
    private final EvaluationRunService runService = mock(EvaluationRunService.class);
    private final MemoryService memoryService = mock(MemoryService.class);
    private final EvaluationReportRenderer renderer = mock(EvaluationReportRenderer.class);
    private final MemoryEvaluationService service = new MemoryEvaluationService(loader, runService,
            memoryService, renderer);

    @Test
    void 记忆命中期望关键词时指标为一() {
        when(loader.loadMemory("memory-retrieval")).thenReturn(
                new DatasetLoader.DatasetResource<>("memory-retrieval", "hash",
                        new MemoryDataset("1.0", List.of(new MemoryDataset.MemoryCase("m1",
                                "订单连接数", "连接数为30"))), "MEMORY"));
        when(runService.start(anyString(), anyString(), anyString(), any(), any(), any(), anyInt()))
                .thenReturn(run());
        when(memoryService.search(eq(1L), eq("订单连接数"), eq(5))).thenReturn(List.of(memory()));
        when(renderer.render(anyString(), any(), any(), any())).thenReturn("report");

        EvaluationReport report = service.evaluate(1L, 5, "memory-retrieval");

        assertThat(report.status()).isEqualTo("SUCCESS");
        assertThat(report.metrics()).containsEntry("recallAtK", 1.0);
        assertThat(report.metrics()).containsEntry("mrr", 1.0);
        verify(runService).recordCase(anyString(), eq("MEMORY"), eq("m1"), eq("订单连接数"),
                any(), any(), any(), eq(true), any(Long.class), eq(null));
    }

    private Memory memory() {
        Memory memory = new Memory();
        memory.setId(1L);
        memory.setUserId(1L);
        memory.setMemoryType(MemoryType.FACT);
        memory.setTitle("订单连接数");
        memory.setContent("订单模块最大连接数为30");
        memory.setSummary("连接数为30");
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setVersion(1);
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }

    private EvaluationRun run() {
        EvaluationRun run = new EvaluationRun();
        run.setRunId("0123456789abcdef0123456789abcdef");
        run.setStartedAt(LocalDateTime.now());
        run.setCaseCount(1);
        run.setStatus("RUNNING");
        return run;
    }
}
