CREATE TABLE IF NOT EXISTS storage_migration_run (
    id VARCHAR(36) PRIMARY KEY,
    source_kind VARCHAR(40) NOT NULL,
    source_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    novel_count INT NOT NULL,
    artifact_count BIGINT NOT NULL,
    version_count BIGINT NOT NULL,
    approval_count BIGINT NOT NULL,
    task_count BIGINT NOT NULL,
    conversation_count BIGINT NOT NULL,
    approved_word_count BIGINT NOT NULL,
    report_json CLOB NOT NULL,
    started_at VARCHAR(40) NOT NULL,
    finished_at VARCHAR(40)
);
