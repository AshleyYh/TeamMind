package com.teammind.evaluation.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teammind.common.exception.BusinessException;
import com.teammind.evaluation.domain.EvaluationCase;
import com.teammind.evaluation.domain.EvaluationRun;
import com.teammind.evaluation.mapper.EvaluationCaseMapper;
import com.teammind.evaluation.mapper.EvaluationRunMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationRunServiceTests {
    private final EvaluationRunMapper runMapper = mock(EvaluationRunMapper.class);
    private final EvaluationCaseMapper caseMapper = mock(EvaluationCaseMapper.class);
    private final EvaluationRunService service = new EvaluationRunService(runMapper, caseMapper,
            new ObjectMapper());

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, EvaluationRun.class);
        TableInfoHelper.initTableInfo(assistant, EvaluationCase.class);
    }

    @Test
    void 创建运行和记录用例() {
        when(runMapper.insert(any(EvaluationRun.class))).thenAnswer(invocation -> {
            EvaluationRun run = invocation.getArgument(0);
            run.setId(1L);
            return 1;
        });

        EvaluationRun run = service.start("RAG", "rag-retrieval", "abc", 1L, List.of(1L),
                List.of("DENSE"), 3);

        assertThat(run.getRunId()).hasSize(32);
        assertThat(run.getStatus()).isEqualTo("RUNNING");
        assertThat(run.getCaseCount()).isEqualTo(3);
        assertThat(run.getKnowledgeBaseIds()).isEqualTo("1");
        assertThat(run.getStrategies()).isEqualTo("DENSE");

        service.recordCase(run.getRunId(), "RAG", "case-1", "问题",
                Map.of("relevant", List.of("a.pdf")), Map.of("documents", List.of("a.pdf")),
                Map.of("recallAtK", 1.0), true, 12, null);

        ArgumentCaptor<EvaluationCase> captor = ArgumentCaptor.forClass(EvaluationCase.class);
        verify(caseMapper).insert(captor.capture());
        assertThat(captor.getValue().getRunId()).isEqualTo(run.getRunId());
        assertThat(captor.getValue().getMetricsJson()).contains("recallAtK");
    }

    @Test
    void 完成运行和失败记录() {
        EvaluationRun run = run();
        when(runMapper.selectOne(any())).thenReturn(run);

        service.finishSuccess(run.getRunId(), Map.of("accuracy", 0.8), "# 报告");
        service.finishFailure(run.getRunId(), "失败");

        ArgumentCaptor<EvaluationRun> captor = ArgumentCaptor.forClass(EvaluationRun.class);
        verify(runMapper, org.mockito.Mockito.times(2)).updateById(captor.capture());
        assertThat(captor.getAllValues().get(0).getStatus()).isEqualTo("SUCCESS");
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo("FAILED");
        assertThat(captor.getAllValues().get(1).getErrorMessage()).isEqualTo("失败");
    }

    @Test
    void 运行查询校验归属() {
        when(runMapper.selectOne(any())).thenReturn(run());

        assertThatThrownBy(() -> service.get(run().getRunId(), 2L))
                .isInstanceOf(BusinessException.class);
        assertThat(service.get(run().getRunId(), 1L).getRunId()).isEqualTo(run().getRunId());
    }

    @Test
    void 用例列表先校验运行归属() {
        when(runMapper.selectOne(any())).thenReturn(run());
        when(caseMapper.selectList(any())).thenReturn(List.of(new EvaluationCase()));

        assertThat(service.cases(run().getRunId(), 1L)).hasSize(1);
    }

    private EvaluationRun run() {
        EvaluationRun run = new EvaluationRun();
        run.setRunId("0123456789abcdef0123456789abcdef");
        run.setOwnerId(1L);
        run.setDatasetType("TASK");
        run.setDatasetName("task-classification");
        run.setStatus("RUNNING");
        run.setCaseCount(1);
        run.setStartedAt(LocalDateTime.now());
        return run;
    }
}
