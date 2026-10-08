CREATE TABLE IF NOT EXISTS shadow_retrieval_run (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, query_text VARCHAR(2000) NOT NULL,
    before_chapter INT NOT NULL, result_limit INT NOT NULL, status VARCHAR(40) NOT NULL,
    hit_count INT NOT NULL, latency_ms BIGINT NOT NULL, created_at VARCHAR(40) NOT NULL, diagnostic VARCHAR(1000),
    CONSTRAINT fk_shadow_retrieval_run_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE INDEX idx_shadow_retrieval_run_novel ON shadow_retrieval_run(novel_id, created_at);

CREATE TABLE IF NOT EXISTS shadow_retrieval_hit (
    id VARCHAR(36) PRIMARY KEY, run_id VARCHAR(36) NOT NULL, novel_id VARCHAR(36) NOT NULL,
    rank_no INT NOT NULL, source_version_id VARCHAR(36) NOT NULL, artifact_id VARCHAR(36) NOT NULL,
    chapter_number INT NOT NULL, score DOUBLE, title VARCHAR(500), summary LONGTEXT,
    CONSTRAINT fk_shadow_retrieval_hit_run FOREIGN KEY (run_id) REFERENCES shadow_retrieval_run(id) ON DELETE CASCADE,
    CONSTRAINT fk_shadow_retrieval_hit_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE INDEX idx_shadow_retrieval_hit_run ON shadow_retrieval_hit(run_id, rank_no);
