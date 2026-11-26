-- Phase 6：完整 Trace/Span、Token 统计、耗时和失败节点。
ALTER TABLE ai_trace ADD COLUMN prompt_tokens BIGINT;
ALTER TABLE ai_trace ADD COLUMN completion_tokens BIGINT;
ALTER TABLE ai_trace ADD COLUMN error_message TEXT;
ALTER TABLE ai_trace ADD COLUMN finished_at TIMESTAMP;

ALTER TABLE ai_trace_span ADD COLUMN input_tokens BIGINT;
ALTER TABLE ai_trace_span ADD COLUMN output_tokens BIGINT;
ALTER TABLE ai_trace_span ADD COLUMN error_type VARCHAR(64);

CREATE INDEX idx_trace_user_created ON ai_trace (user_id, created_at DESC);
CREATE INDEX idx_span_trace_created ON ai_trace_span (trace_id, created_at);
