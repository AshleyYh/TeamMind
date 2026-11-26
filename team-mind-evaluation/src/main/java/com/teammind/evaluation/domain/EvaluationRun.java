package com.teammind.evaluation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 一次评测运行；runId 对外暴露，数据库主键不参与业务查询。 */
@TableName("ai_evaluation_run")
public class EvaluationRun {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String runId;
    private Long ownerId;
    private String datasetType;
    private String datasetName;
    private String datasetHash;
    private String knowledgeBaseIds;
    private String strategies;
    private String status;
    private Integer caseCount;
    private String metricsJson;
    private String reportMarkdown;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getDatasetType() { return datasetType; }
    public void setDatasetType(String datasetType) { this.datasetType = datasetType; }
    public String getDatasetName() { return datasetName; }
    public void setDatasetName(String datasetName) { this.datasetName = datasetName; }
    public String getDatasetHash() { return datasetHash; }
    public void setDatasetHash(String datasetHash) { this.datasetHash = datasetHash; }
    public String getKnowledgeBaseIds() { return knowledgeBaseIds; }
    public void setKnowledgeBaseIds(String knowledgeBaseIds) { this.knowledgeBaseIds = knowledgeBaseIds; }
    public String getStrategies() { return strategies; }
    public void setStrategies(String strategies) { this.strategies = strategies; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getCaseCount() { return caseCount; }
    public void setCaseCount(Integer caseCount) { this.caseCount = caseCount; }
    public String getMetricsJson() { return metricsJson; }
    public void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }
    public String getReportMarkdown() { return reportMarkdown; }
    public void setReportMarkdown(String reportMarkdown) { this.reportMarkdown = reportMarkdown; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
