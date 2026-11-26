package com.teammind.evaluation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 单个评测用例的期望、实际、指标和失败原因。 */
@TableName("ai_evaluation_case")
public class EvaluationCase {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String runId;
    private String datasetType;
    private String caseId;
    private String queryText;
    private String expectedJson;
    private String actualJson;
    private String metricsJson;
    private Boolean success;
    private Long duration;
    private String errorMessage;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getDatasetType() { return datasetType; }
    public void setDatasetType(String datasetType) { this.datasetType = datasetType; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getQueryText() { return queryText; }
    public void setQueryText(String queryText) { this.queryText = queryText; }
    public String getExpectedJson() { return expectedJson; }
    public void setExpectedJson(String expectedJson) { this.expectedJson = expectedJson; }
    public String getActualJson() { return actualJson; }
    public void setActualJson(String actualJson) { this.actualJson = actualJson; }
    public String getMetricsJson() { return metricsJson; }
    public void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public Long getDuration() { return duration; }
    public void setDuration(Long duration) { this.duration = duration; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
