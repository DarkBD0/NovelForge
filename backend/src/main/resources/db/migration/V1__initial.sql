-- V1: one transactional aggregate per novel. Versions and audit records are append-only in the aggregate.
CREATE TABLE IF NOT EXISTS novels (
    id VARCHAR(36) PRIMARY KEY,
    document CLOB NOT NULL,
    created_at VARCHAR(40) NOT NULL
);
