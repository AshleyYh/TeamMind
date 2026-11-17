-- Phase 2：支持同一文档名称的版本更新，并约束知识库名称。
ALTER TABLE knowledge_document DROP CONSTRAINT IF EXISTS uk_document_name_version;
CREATE UNIQUE INDEX IF NOT EXISTS uk_document_active_name
    ON knowledge_document (knowledge_base_id, document_name) WHERE NOT deleted;
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_base_owner_name_active
    ON knowledge_base (owner_id, name) WHERE NOT deleted;
