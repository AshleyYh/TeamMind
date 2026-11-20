package com.teammind.observability.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_evidence")
public class Evidence {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String traceId;
    private Long ownerId;
    private Long documentId;
    private Long chunkId;
    private String documentName;
    private Integer documentVersion;
    private String content;
    private Integer pageNumber;
    private String retrievalType;
    private Integer denseRank;
    private Integer bm25Rank;
    private Double denseScore;
    private Double bm25Score;
    private Double rrfScore;
    private Double rerankScore;
    private Boolean reranked;
    private Boolean cited;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Long getChunkId() { return chunkId; }
    public void setChunkId(Long chunkId) { this.chunkId = chunkId; }
    public String getDocumentName() { return documentName; }
    public void setDocumentName(String documentName) { this.documentName = documentName; }
    public Integer getDocumentVersion() { return documentVersion; }
    public void setDocumentVersion(Integer documentVersion) { this.documentVersion = documentVersion; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getPageNumber() { return pageNumber; }
    public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }
    public String getRetrievalType() { return retrievalType; }
    public void setRetrievalType(String retrievalType) { this.retrievalType = retrievalType; }
    public Integer getDenseRank() { return denseRank; }
    public void setDenseRank(Integer denseRank) { this.denseRank = denseRank; }
    public Integer getBm25Rank() { return bm25Rank; }
    public void setBm25Rank(Integer bm25Rank) { this.bm25Rank = bm25Rank; }
    public Double getDenseScore() { return denseScore; }
    public void setDenseScore(Double denseScore) { this.denseScore = denseScore; }
    public Double getBm25Score() { return bm25Score; }
    public void setBm25Score(Double bm25Score) { this.bm25Score = bm25Score; }
    public Double getRrfScore() { return rrfScore; }
    public void setRrfScore(Double rrfScore) { this.rrfScore = rrfScore; }
    public Double getRerankScore() { return rerankScore; }
    public void setRerankScore(Double rerankScore) { this.rerankScore = rerankScore; }
    public Boolean getReranked() { return reranked; }
    public void setReranked(Boolean reranked) { this.reranked = reranked; }
    public Boolean getCited() { return cited; }
    public void setCited(Boolean cited) { this.cited = cited; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
