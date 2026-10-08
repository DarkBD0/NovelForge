CREATE TABLE IF NOT EXISTS storage_migration_run (
    id CHAR(36) PRIMARY KEY,
    source_kind VARCHAR(40) NOT NULL,
    source_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    novel_count INT NOT NULL,
    artifact_count BIGINT NOT NULL,
    version_count BIGINT NOT NULL,
    approval_count BIGINT NOT NULL,
    task_count BIGINT NOT NULL,
    conversation_count BIGINT NOT NULL,
    approved_word_count BIGINT NOT NULL,
    report_json LONGTEXT NOT NULL,
    started_at VARCHAR(40) NOT NULL,
    finished_at VARCHAR(40)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
