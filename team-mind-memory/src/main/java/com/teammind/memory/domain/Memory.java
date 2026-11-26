package com.teammind.memory.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 长期记忆。草稿必须经过确认才能变为 ACTIVE 并参与检索；
 * 更新会递增 version，失效和删除保留来源用于追溯。
 */
@TableName("ai_memory")
public class Memory {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private MemoryType memoryType;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String title;
    private String content;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String summary;
    private String sourceConversationId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sourceTraceId;
    private Integer version;
    private MemoryStatus status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Double confidence;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastUsedAt;
    @TableLogic
    private Boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public MemoryType getMemoryType() { return memoryType; }
    public void setMemoryType(MemoryType memoryType) { this.memoryType = memoryType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getSourceConversationId() { return sourceConversationId; }
    public void setSourceConversationId(String sourceConversationId) { this.sourceConversationId = sourceConversationId; }
    public String getSourceTraceId() { return sourceTraceId; }
    public void setSourceTraceId(String sourceTraceId) { this.sourceTraceId = sourceTraceId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public MemoryStatus getStatus() { return status; }
    public void setStatus(MemoryStatus status) { this.status = status; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
