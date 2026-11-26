package com.teammind.evaluation.application;

import com.teammind.ai.agent.task.TaskType;
import com.teammind.ai.agent.task.TaskUnderstanding;
import com.teammind.ai.agent.task.TaskUnderstandingService;
import com.teammind.evaluation.dataset.DatasetLoader;
import com.teammind.evaluation.dataset.TaskDataset;
import com.teammind.evaluation.domain.EvaluationRun;
import com.teammind.evaluation.report.EvaluationReport;
import com.teammind.evaluation.report.EvaluationReportRenderer;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskEvaluationServiceTests {
    private final DatasetLoader loader = mock(DatasetLoader.class);
    private final EvaluationRunService runService = mock(EvaluationRunService.class);
    private final TaskUnderstandingService understandingService = mock(TaskUnderstandingService.class);
    private final EvaluationReportRenderer renderer = mock(EvaluationReportRenderer.class);
    private final TaskEvaluationService service = new TaskEvaluationService(loader, runService,
            understandingService, renderer);

    @Test
    void 任务类型和工具正确时准确率为一() {
        TaskDataset dataset = new TaskDataset("1.0", List.of(
                new TaskDataset.TaskCase("t1", "你好", TaskType.GENERAL_CHAT, List.of(), List.of()),
                new TaskDataset.TaskCase("t2", "连接数", TaskType.KNOWLEDGE_QA,
                        List.of("knowledgeSearch"), List.of())));
        when(loader.loadTask("task-classification")).thenReturn(
                new DatasetLoader.DatasetResource<>("task-classification", "hash", dataset, "TASK"));
        when(runService.start(anyString(), anyString(), anyString(), any(), any(), any(), anyInt()))
                .thenReturn(run());
        when(understandingService.understand(any())).thenReturn(
                task(TaskType.GENERAL_CHAT, List.of()),
                task(TaskType.KNOWLEDGE_QA, List.of("knowledgeSearch")));
        when(renderer.render(anyString(), any(), any(), any())).thenReturn("report");

        EvaluationReport report = service.evaluate(1L, "task-classification", List.of(1L));

        assertThat(report.status()).isEqualTo("SUCCESS");
        assertThat(report.metrics()).containsEntry("accuracy", 1.0);
        assertThat(report.metrics()).containsEntry("toolSelectionAccuracy", 1.0);
        assertThat(report.cases()).hasSize(2);
        verify(runService, org.mockito.Mockito.times(2)).recordCase(anyString(), eq("TASK"),
                anyString(), anyString(), any(), any(), any(), eq(true), any(Long.class), eq(null));
        verify(runService).finishSuccess(anyString(), any(), eq("report"));
    }

    private TaskUnderstanding task(TaskType type, List<String> tools) {
        return new TaskUnderstanding(type, "intent", List.of(), List.of(), List.of(1L), null,
                tools, 0.9, false, List.of());
    }

    private EvaluationRun run() {
        EvaluationRun run = new EvaluationRun();
        run.setRunId("0123456789abcdef0123456789abcdef");
        run.setStartedAt(LocalDateTime.now());
        run.setCaseCount(2);
        run.setStatus("RUNNING");
        return run;
    }
}
