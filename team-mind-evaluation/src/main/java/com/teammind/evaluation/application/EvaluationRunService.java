package com.teammind.evaluation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import com.teammind.evaluation.domain.EvaluationCase;
import com.teammind.evaluation.domain.EvaluationRun;
import com.teammind.evaluation.mapper.EvaluationCaseMapper;
import com.teammind.evaluation.mapper.EvaluationRunMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** 评测运行和用例结果的持久化与查询。 */
@Service
public class EvaluationRunService {
    private final EvaluationRunMapper runMapper;
    private final EvaluationCaseMapper caseMapper;
    private final ObjectMapper objectMapper;

    public EvaluationRunService(EvaluationRunMapper runMapper, EvaluationCaseMapper caseMapper,
            ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.caseMapper = caseMapper;
        this.objectMapper = objectMapper;
    }

    public EvaluationRun start(String datasetType, String datasetName, String datasetHash, Long ownerId,
            List<Long> knowledgeBaseIds, List<String> strategies, int caseCount) {
        EvaluationRun run = new EvaluationRun();
        run.setRunId(UUID.randomUUID().toString().replace("-", ""));
        run.setOwnerId(ownerId);
        run.setDatasetType(datasetType);
        run.setDatasetName(datasetName);
        run.setDatasetHash(datasetHash);
        run.setKnowledgeBaseIds(knowledgeBaseIds == null ? "" : knowledgeBaseIds.stream()
                .map(String::valueOf).collect(Collectors.joining(",")));
        run.setStrategies(strategies == null ? "" : String.join(",", strategies));
        run.setStatus("RUNNING");
        run.setCaseCount(caseCount);
        run.setStartedAt(LocalDateTime.now());
        run.setCreatedAt(LocalDateTime.now());
        runMapper.insert(run);
        return run;
    }

    public void recordCase(String runId, String datasetType, String caseId, String query,
            Map<String, Object> expected, Map<String, Object> actual, Map<String, Object> metrics,
            boolean success, long durationMillis, String errorMessage) {
        EvaluationCase item = new EvaluationCase();
        item.setRunId(runId);
        item.setDatasetType(datasetType);
        item.setCaseId(caseId);
        item.setQueryText(query);
        item.setExpectedJson(toJson(expected));
        item.setActualJson(toJson(actual));
        item.setMetricsJson(toJson(metrics));
        item.setSuccess(success);
        item.setDuration(Math.max(durationMillis, 0));
        item.setErrorMessage(errorMessage);
        item.setCreatedAt(LocalDateTime.now());
        caseMapper.insert(item);
    }

    public void finishSuccess(String runId, Map<String, Object> metrics, String reportMarkdown) {
        EvaluationRun update = new EvaluationRun();
        update.setId(findByRunId(runId).getId());
        update.setStatus("SUCCESS");
        update.setMetricsJson(toJson(metrics));
        update.setReportMarkdown(reportMarkdown);
        update.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(update);
    }

    public void finishFailure(String runId, String errorMessage) {
        EvaluationRun update = new EvaluationRun();
        update.setId(findByRunId(runId).getId());
        update.setStatus("FAILED");
        update.setErrorMessage(errorMessage);
        update.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(update);
    }

    public List<EvaluationRun> list(Long ownerId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return runMapper.selectList(new LambdaQueryWrapper<EvaluationRun>()
                .eq(EvaluationRun::getOwnerId, ownerId)
                .orderByDesc(EvaluationRun::getCreatedAt)
                .last("LIMIT " + safeLimit));
    }

    public EvaluationRun get(String runId, Long ownerId) {
        EvaluationRun run = findByRunId(runId);
        if (!run.getOwnerId().equals(ownerId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "评测运行不存在或不可访问");
        }
        return run;
    }

    public List<EvaluationCase> cases(String runId, Long ownerId) {
        get(runId, ownerId);
        return caseMapper.selectList(new LambdaQueryWrapper<EvaluationCase>()
                .eq(EvaluationCase::getRunId, runId)
                .orderByAsc(EvaluationCase::getId));
    }

    private EvaluationRun findByRunId(String runId) {
        EvaluationRun run = runMapper.selectOne(new LambdaQueryWrapper<EvaluationRun>()
                .eq(EvaluationRun::getRunId, runId));
        if (run == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "评测运行不存在");
        }
        return run;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("评测结果序列化失败", exception);
        }
    }
}
