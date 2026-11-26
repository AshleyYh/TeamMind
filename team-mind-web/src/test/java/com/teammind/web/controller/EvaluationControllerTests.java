package com.teammind.web.controller;

import com.teammind.evaluation.application.EvaluationRunService;
import com.teammind.evaluation.application.MemoryEvaluationService;
import com.teammind.evaluation.application.RagEvaluationService;
import com.teammind.evaluation.application.TaskEvaluationService;
import com.teammind.evaluation.dataset.DatasetLoader;
import com.teammind.evaluation.domain.EvaluationCase;
import com.teammind.evaluation.domain.EvaluationRun;
import com.teammind.evaluation.report.EvaluationReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EvaluationControllerTests {
    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EvaluationRunService runService;
    @MockitoBean
    private TaskEvaluationService taskEvaluationService;
    @MockitoBean
    private RagEvaluationService ragEvaluationService;
    @MockitoBean
    private MemoryEvaluationService memoryEvaluationService;
    @MockitoBean
    private DatasetLoader datasetLoader;

    @Test
    void 查询数据集和任务评测() throws Exception {
        when(datasetLoader.list()).thenReturn(List.of(new DatasetLoader.DatasetDescriptor(
                "TASK", "task-classification", "1.0", 5, "hash")));
        when(taskEvaluationService.evaluate(any(), any(), any())).thenReturn(report());

        mockMvc.perform(get("/api/v1/evaluation/datasets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("task-classification"));

        mockMvc.perform(post("/api/v1/evaluation/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":1,"dataset":"task-classification","knowledgeBaseIds":[1]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(RUN_ID))
                .andExpect(jsonPath("$.data.metrics.accuracy").value(1.0));
    }

    @Test
    void RAG评测缺少知识库返回参数错误() throws Exception {
        mockMvc.perform(post("/api/v1/evaluation/rag")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":1,"knowledgeBaseIds":[],"topK":5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }

    @Test
    void 查询运行详情和Markdown报告() throws Exception {
        EvaluationRun run = run();
        when(runService.list(1L, 20)).thenReturn(List.of(run));
        when(runService.get(RUN_ID, 1L)).thenReturn(run);
        EvaluationCase item = new EvaluationCase();
        item.setCaseId("case-1");
        item.setQueryText("问题");
        item.setExpectedJson("{\"taskType\":\"GENERAL_CHAT\"}");
        item.setActualJson("{\"taskType\":\"GENERAL_CHAT\"}");
        item.setMetricsJson("{\"typeCorrect\":true}");
        item.setSuccess(true);
        item.setDuration(10L);
        when(runService.cases(RUN_ID, 1L)).thenReturn(List.of(item));

        mockMvc.perform(get("/api/v1/evaluation/runs").param("ownerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].runId").value(RUN_ID))
                .andExpect(jsonPath("$.data[0].metrics.accuracy").value(1.0));

        mockMvc.perform(get("/api/v1/evaluation/runs/" + RUN_ID).param("ownerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cases[0].caseId").value("case-1"))
                .andExpect(jsonPath("$.data.cases[0].metrics.typeCorrect").value(true));

        mockMvc.perform(get("/api/v1/evaluation/runs/" + RUN_ID + "/report").param("ownerId", "1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/markdown")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("# 评测报告")));
    }

    @Test
    void 非法运行ID返回参数错误() throws Exception {
        mockMvc.perform(get("/api/v1/evaluation/runs/not-a-run").param("ownerId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }

    private EvaluationReport report() {
        return new EvaluationReport(RUN_ID, "TASK", "task-classification", "hash", "SUCCESS", 1,
                Map.of("accuracy", 1.0), List.of(), "# 评测报告", LocalDateTime.now(),
                LocalDateTime.now());
    }

    private EvaluationRun run() {
        EvaluationRun run = new EvaluationRun();
        run.setRunId(RUN_ID);
        run.setOwnerId(1L);
        run.setDatasetType("TASK");
        run.setDatasetName("task-classification");
        run.setDatasetHash("hash");
        run.setStatus("SUCCESS");
        run.setCaseCount(1);
        run.setMetricsJson("{\"accuracy\":1.0}");
        run.setReportMarkdown("# 评测报告");
        run.setStartedAt(LocalDateTime.now());
        run.setFinishedAt(LocalDateTime.now());
        return run;
    }
}
