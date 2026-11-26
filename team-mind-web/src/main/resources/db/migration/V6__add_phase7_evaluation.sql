-- Phase 7：评测运行、用例结果和 Markdown 报告持久化。
CREATE TABLE ai_evaluation_run (
    id                  BIGINT PRIMARY KEY,
    run_id              VARCHAR(64) NOT NULL,
    owner_id            BIGINT NOT NULL,
    dataset_type        VARCHAR(32) NOT NULL,
    dataset_name        VARCHAR(128) NOT NULL,
    dataset_hash        VARCHAR(64),
    knowledge_base_ids  TEXT,
    strategies          TEXT,
    status              VARCHAR(32) NOT NULL,
    case_count          INTEGER NOT NULL DEFAULT 0,
    metrics_json        TEXT,
    report_markdown     TEXT,
    error_message       TEXT,
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_eval_run_identifier UNIQUE (run_id),
    CONSTRAINT fk_eval_run_owner FOREIGN KEY (owner_id) REFERENCES sys_user (id),
    CONSTRAINT ck_eval_run_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED'))
);

CREATE TABLE ai_evaluation_case (
    id                  BIGINT PRIMARY KEY,
    run_id              VARCHAR(64) NOT NULL,
    dataset_type        VARCHAR(32) NOT NULL,
    case_id             VARCHAR(128) NOT NULL,
    query_text          TEXT NOT NULL,
    expected_json       TEXT,
    actual_json         TEXT,
    metrics_json        TEXT,
    success             BOOLEAN NOT NULL DEFAULT FALSE,
    duration            BIGINT,
    error_message       TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_eval_case_run FOREIGN KEY (run_id) REFERENCES ai_evaluation_run (run_id) ON DELETE CASCADE,
    CONSTRAINT ck_eval_case_duration CHECK (duration IS NULL OR duration >= 0)
);

CREATE INDEX idx_eval_run_owner_created ON ai_evaluation_run (owner_id, created_at DESC);
CREATE INDEX idx_eval_case_run ON ai_evaluation_case (run_id);
