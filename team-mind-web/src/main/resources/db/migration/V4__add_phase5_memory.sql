-- Phase 5：长期记忆补充标题、摘要、来源 Trace、置信度、使用时间和逻辑删除。
ALTER TABLE ai_memory ADD COLUMN title VARCHAR(255);
ALTER TABLE ai_memory ADD COLUMN summary TEXT;
ALTER TABLE ai_memory ADD COLUMN source_trace_id VARCHAR(64);
ALTER TABLE ai_memory ADD COLUMN confidence DOUBLE PRECISION;
ALTER TABLE ai_memory ADD COLUMN last_used_at TIMESTAMP;
ALTER TABLE ai_memory ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE ai_memory ADD CONSTRAINT ck_memory_confidence
    CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1));

CREATE INDEX idx_memory_user_status_updated
    ON ai_memory (user_id, status, updated_at DESC) WHERE NOT deleted;
CREATE INDEX idx_memory_source_trace ON ai_memory (source_trace_id);
