-- Phase 3：持久化 BM25 词元，扩展可追溯的证据快照。
ALTER TABLE knowledge_chunk ADD COLUMN search_tokens TEXT;
ALTER TABLE knowledge_chunk ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    to_tsvector('simple'::regconfig, coalesce(search_tokens, ''))
) STORED;

ALTER TABLE ai_trace ADD COLUMN knowledge_base_ids TEXT;
ALTER TABLE ai_trace ADD COLUMN query TEXT;
ALTER TABLE ai_trace ADD COLUMN strategy VARCHAR(32);
ALTER TABLE ai_trace ADD COLUMN answer TEXT;

-- Evidence 必须保留历史原文快照，不能因文档重处理或删除而阻止清理当前知识。
ALTER TABLE ai_evidence DROP CONSTRAINT IF EXISTS fk_evidence_document;
ALTER TABLE ai_evidence DROP CONSTRAINT IF EXISTS fk_evidence_chunk;

ALTER TABLE ai_evidence ADD COLUMN owner_id BIGINT;
ALTER TABLE ai_evidence ADD COLUMN document_name VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE ai_evidence ADD COLUMN content TEXT NOT NULL DEFAULT '';
ALTER TABLE ai_evidence ADD COLUMN page_number INTEGER;
ALTER TABLE ai_evidence ADD COLUMN dense_rank INTEGER;
ALTER TABLE ai_evidence ADD COLUMN bm25_rank INTEGER;
ALTER TABLE ai_evidence ADD COLUMN dense_score DOUBLE PRECISION;
ALTER TABLE ai_evidence ADD COLUMN bm25_score DOUBLE PRECISION;
ALTER TABLE ai_evidence ADD COLUMN rrf_score DOUBLE PRECISION;
ALTER TABLE ai_evidence ADD COLUMN reranked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_evidence ADD COLUMN cited BOOLEAN NOT NULL DEFAULT FALSE;

-- 兼容升级前已经存在的证据：可从原业务表恢复的字段立即回填。
UPDATE ai_evidence e SET owner_id = t.user_id
FROM ai_trace t WHERE e.trace_id = t.trace_id AND e.owner_id IS NULL;
UPDATE ai_evidence e SET document_name = d.document_name
FROM knowledge_document d WHERE e.document_id = d.id AND e.document_name = '';
UPDATE ai_evidence e SET content = c.content, page_number = COALESCE(e.page_number, c.page_number)
FROM knowledge_chunk c WHERE e.chunk_id = c.id AND e.content = '';

ALTER TABLE ai_evidence ALTER COLUMN retrieval_score TYPE DOUBLE PRECISION;
ALTER TABLE ai_evidence ALTER COLUMN rerank_score TYPE DOUBLE PRECISION;
ALTER TABLE ai_evidence ADD CONSTRAINT fk_evidence_owner FOREIGN KEY (owner_id) REFERENCES sys_user (id);
ALTER TABLE ai_evidence ADD CONSTRAINT ck_evidence_dense_rank CHECK (dense_rank IS NULL OR dense_rank > 0);
ALTER TABLE ai_evidence ADD CONSTRAINT ck_evidence_bm25_rank CHECK (bm25_rank IS NULL OR bm25_rank > 0);

CREATE INDEX idx_chunk_search_vector ON knowledge_chunk USING GIN (search_vector);
CREATE INDEX idx_evidence_owner_trace ON ai_evidence (owner_id, trace_id);
