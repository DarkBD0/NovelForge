CREATE TABLE IF NOT EXISTS novel_project (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(100) NOT NULL,
    title VARCHAR(300) NOT NULL,
    synopsis LONGTEXT NOT NULL,
    requirements LONGTEXT NOT NULL,
    target_words BIGINT NOT NULL,
    approved_max_words BIGINT NOT NULL,
    auto_style_enabled TINYINT(1),
    revision BIGINT NOT NULL,
    status VARCHAR(40) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    record_hash VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS artifact (
    id VARCHAR(36) PRIMARY KEY,
    novel_id VARCHAR(36) NOT NULL,
    ordinal_no INT NOT NULL,
    kind VARCHAR(30) NOT NULL,
    chapter_number INT NOT NULL,
    batch_number INT NOT NULL,
    approved_version_id VARCHAR(36),
    needs_revision TINYINT(1) NOT NULL,
    CONSTRAINT fk_artifact_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE,
    CONSTRAINT uq_artifact_order UNIQUE (novel_id, ordinal_no)
);
CREATE INDEX idx_artifact_lookup ON artifact(novel_id, kind, chapter_number, batch_number);

CREATE TABLE IF NOT EXISTS artifact_version (
    id VARCHAR(36) PRIMARY KEY,
    novel_id VARCHAR(36) NOT NULL,
    artifact_id VARCHAR(36) NOT NULL,
    ordinal_no INT NOT NULL,
    base_version_id VARCHAR(36),
    source VARCHAR(80),
    title VARCHAR(500),
    content LONGTEXT,
    summary LONGTEXT,
    metadata_json LONGTEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    word_count BIGINT NOT NULL,
    based_on_revision BIGINT NOT NULL,
    dismissed TINYINT(1) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    CONSTRAINT fk_version_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE,
    CONSTRAINT fk_version_artifact FOREIGN KEY (artifact_id) REFERENCES artifact(id) ON DELETE CASCADE,
    CONSTRAINT uq_version_order UNIQUE (artifact_id, ordinal_no)
);
CREATE INDEX idx_version_source ON artifact_version(novel_id, artifact_id, created_at);

CREATE TABLE IF NOT EXISTS generation_task (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    action VARCHAR(40), status VARCHAR(40), artifact_id VARCHAR(36), request_key VARCHAR(160),
    created_at VARCHAR(40), finished_at VARCHAR(40), payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_task_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE INDEX idx_task_state ON generation_task(novel_id, status, created_at);

CREATE TABLE IF NOT EXISTS source_snapshot (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    task_id VARCHAR(36), context_hash VARCHAR(64), context_json LONGTEXT NOT NULL,
    created_at VARCHAR(40), payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_snapshot_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE INDEX idx_snapshot_task ON source_snapshot(novel_id, task_id);

CREATE TABLE IF NOT EXISTS agent_run (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    task_id VARCHAR(36), source_snapshot_id VARCHAR(36), role VARCHAR(80), operation VARCHAR(120), status VARCHAR(40),
    started_at VARCHAR(40), finished_at VARCHAR(40), payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_agent_run_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE INDEX idx_agent_run_task ON agent_run(novel_id, task_id, status);

CREATE TABLE IF NOT EXISTS shadow_review (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    artifact_id VARCHAR(36), version_id VARCHAR(36), checker VARCHAR(100), status VARCHAR(40), created_at VARCHAR(40),
    payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_shadow_review_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE INDEX idx_shadow_review_target ON shadow_review(novel_id, artifact_id, version_id);

CREATE TABLE IF NOT EXISTS professional_replay_batch (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    status VARCHAR(40), created_at VARCHAR(40), payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_replay_batch_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS professional_replay_item (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, batch_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    status VARCHAR(40), checker VARCHAR(100), artifact_id VARCHAR(36), version_id VARCHAR(36),
    payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_replay_item_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE,
    CONSTRAINT fk_replay_item_batch FOREIGN KEY (batch_id) REFERENCES professional_replay_batch(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS outline_pipeline_workspace (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    task_id VARCHAR(36), status VARCHAR(40), current_step VARCHAR(80), created_at VARCHAR(40), finished_at VARCHAR(40),
    payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_outline_pipeline_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS conversation_session (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    thread_id VARCHAR(36), scope VARCHAR(40), target_artifact_id VARCHAR(36), base_version_id VARCHAR(36),
    base_revision BIGINT NOT NULL, created_at VARCHAR(40), updated_at VARCHAR(40), payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_conversation_session_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE INDEX idx_conversation_thread ON conversation_session(novel_id, thread_id, updated_at);

CREATE TABLE IF NOT EXISTS conversation_message (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, session_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    role VARCHAR(30), content LONGTEXT NOT NULL, created_at VARCHAR(40), record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_message_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE,
    CONSTRAINT fk_message_session FOREIGN KEY (session_id) REFERENCES conversation_session(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS conversation_turn (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, session_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    status VARCHAR(40), stage VARCHAR(80), request_key VARCHAR(160), started_at VARCHAR(40), finished_at VARCHAR(40),
    payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_turn_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE,
    CONSTRAINT fk_turn_session FOREIGN KEY (session_id) REFERENCES conversation_session(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS conversation_decision (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, session_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    decision_type VARCHAR(40), status VARCHAR(40), payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_decision_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE,
    CONSTRAINT fk_decision_session FOREIGN KEY (session_id) REFERENCES conversation_session(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS action_proposal (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, session_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    operation VARCHAR(100), status VARCHAR(40), base_revision BIGINT, task_id VARCHAR(36),
    payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_action_proposal_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE,
    CONSTRAINT fk_action_proposal_session FOREIGN KEY (session_id) REFERENCES conversation_session(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS project_update_proposal (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, session_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    project_field VARCHAR(40), status VARCHAR(40), base_revision BIGINT,
    payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_project_update_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_update_session FOREIGN KEY (session_id) REFERENCES conversation_session(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS conversation_brief (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    session_id VARCHAR(36), scope VARCHAR(40), target_artifact_id VARCHAR(36), base_version_id VARCHAR(36), base_revision BIGINT,
    hash VARCHAR(64), created_at VARCHAR(40), payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_conversation_brief_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS project_brief_change (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    previous_revision BIGINT, new_revision BIGINT, created_at VARCHAR(40), payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_project_brief_change_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS change_request (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    source_artifact_id VARCHAR(36), created_at VARCHAR(40), payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_change_request_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS approval (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    artifact_id VARCHAR(36), version_id VARCHAR(36), revision BIGINT, approved_at VARCHAR(40), payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_approval_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE,
    CONSTRAINT uq_approval_order UNIQUE (novel_id, ordinal_no)
);
CREATE TABLE IF NOT EXISTS budget_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    changed_at VARCHAR(40), payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_budget_change_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE,
    CONSTRAINT uq_budget_change_order UNIQUE (novel_id, ordinal_no)
);
CREATE TABLE IF NOT EXISTS completion_check (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, ordinal_no INT NOT NULL,
    revision BIGINT, word_count BIGINT, checked_at VARCHAR(40), payload_json LONGTEXT NOT NULL, record_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_completion_check_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS state_delta (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, artifact_version_id VARCHAR(36) NOT NULL,
    status VARCHAR(40) NOT NULL, schema_version INT NOT NULL, payload_json LONGTEXT NOT NULL, payload_hash VARCHAR(64) NOT NULL,
    created_at VARCHAR(40) NOT NULL, decided_at VARCHAR(40),
    CONSTRAINT fk_state_delta_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS canon_entity (
    id VARCHAR(80) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, entity_type VARCHAR(40) NOT NULL,
    canonical_name VARCHAR(300) NOT NULL, aliases_json LONGTEXT NOT NULL, description LONGTEXT,
    source_version_id VARCHAR(36) NOT NULL, valid_from_chapter INT NOT NULL, valid_to_chapter INT, status VARCHAR(40) NOT NULL,
    CONSTRAINT fk_canon_entity_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE INDEX idx_canon_entity_name ON canon_entity(novel_id, entity_type, canonical_name);
CREATE TABLE IF NOT EXISTS canon_fact (
    id VARCHAR(80) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, entity_id VARCHAR(80), fact_type VARCHAR(80) NOT NULL,
    fact_key VARCHAR(200) NOT NULL, detail LONGTEXT NOT NULL, fact_state VARCHAR(80), source_version_id VARCHAR(36) NOT NULL,
    valid_from_chapter INT NOT NULL, valid_to_chapter INT, status VARCHAR(40) NOT NULL,
    CONSTRAINT fk_canon_fact_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE INDEX idx_canon_fact_lookup ON canon_fact(novel_id, fact_type, fact_key, valid_from_chapter);
CREATE TABLE IF NOT EXISTS story_event (
    id VARCHAR(80) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, event_type VARCHAR(80), title VARCHAR(500), summary LONGTEXT NOT NULL,
    chapter_number INT NOT NULL, sequence_no INT NOT NULL, time_text VARCHAR(300), location_entity_id VARCHAR(80),
    source_version_id VARCHAR(36) NOT NULL, status VARCHAR(40) NOT NULL,
    CONSTRAINT fk_story_event_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE INDEX idx_story_event_order ON story_event(novel_id, chapter_number, sequence_no);
CREATE TABLE IF NOT EXISTS entity_relation (
    id VARCHAR(80) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, from_entity_id VARCHAR(80) NOT NULL,
    relation_type VARCHAR(80) NOT NULL, to_entity_id VARCHAR(80) NOT NULL, detail LONGTEXT,
    source_version_id VARCHAR(36) NOT NULL, valid_from_chapter INT NOT NULL, valid_to_chapter INT, status VARCHAR(40) NOT NULL,
    CONSTRAINT fk_entity_relation_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE INDEX idx_entity_relation_path ON entity_relation(novel_id, from_entity_id, relation_type, to_entity_id);
CREATE TABLE IF NOT EXISTS foreshadow (
    id VARCHAR(80) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, title VARCHAR(500), description LONGTEXT NOT NULL,
    planted_chapter INT, developed_chapters_json LONGTEXT NOT NULL, resolved_chapter INT, status VARCHAR(40) NOT NULL,
    source_version_id VARCHAR(36) NOT NULL,
    CONSTRAINT fk_foreshadow_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS version_dependency (
    id VARCHAR(80) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, from_version_id VARCHAR(36) NOT NULL,
    dependency_type VARCHAR(80) NOT NULL, to_reference_id VARCHAR(100) NOT NULL, created_at VARCHAR(40) NOT NULL,
    CONSTRAINT fk_version_dependency_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS context_selection (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, source_snapshot_id VARCHAR(36) NOT NULL,
    source_type VARCHAR(80) NOT NULL, source_id VARCHAR(100) NOT NULL, source_version_id VARCHAR(36), chapter_number INT,
    confirmed TINYINT(1) NOT NULL, selection_reason VARCHAR(500) NOT NULL, omitted TINYINT(1) NOT NULL, omission_reason VARCHAR(500),
    CONSTRAINT fk_context_selection_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS model_call (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, agent_run_id VARCHAR(36), provider VARCHAR(100), model_name VARCHAR(200),
    prompt_version VARCHAR(100), input_chars BIGINT, output_tokens BIGINT, reasoning_tokens BIGINT, latency_ms BIGINT,
    status VARCHAR(40) NOT NULL, diagnostic_id VARCHAR(80), started_at VARCHAR(40), finished_at VARCHAR(40),
    CONSTRAINT fk_model_call_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS agent_tool_policy (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36), role VARCHAR(80) NOT NULL, tool_id VARCHAR(120) NOT NULL,
    read_only TINYINT(1) NOT NULL, enabled TINYINT(1) NOT NULL, policy_version VARCHAR(80) NOT NULL,
    CONSTRAINT fk_tool_policy_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS tool_call (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, agent_run_id VARCHAR(36) NOT NULL, tool_id VARCHAR(120) NOT NULL,
    source_snapshot_id VARCHAR(36) NOT NULL, request_hash VARCHAR(64) NOT NULL, result_hash VARCHAR(64),
    status VARCHAR(40) NOT NULL, started_at VARCHAR(40), finished_at VARCHAR(40), diagnostic LONGTEXT,
    CONSTRAINT fk_tool_call_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS outbox_event (
    id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, target VARCHAR(40) NOT NULL, event_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL, source_version_id VARCHAR(36), payload_json LONGTEXT NOT NULL, payload_hash VARCHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL, attempts INT NOT NULL, created_at VARCHAR(40) NOT NULL, next_attempt_at VARCHAR(40), processed_at VARCHAR(40), error LONGTEXT,
    CONSTRAINT fk_outbox_novel FOREIGN KEY (novel_id) REFERENCES novel_project(id) ON DELETE CASCADE
);
CREATE INDEX idx_outbox_pending ON outbox_event(target, status, next_attempt_at);
CREATE TABLE IF NOT EXISTS projection_checkpoint (
    target VARCHAR(80) PRIMARY KEY, last_event_id VARCHAR(36), updated_at VARCHAR(40) NOT NULL, metadata_json LONGTEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS embedding_cache (
    chunk_hash VARCHAR(64) NOT NULL, model_name VARCHAR(200) NOT NULL, dimensions INT NOT NULL,
    vector_bytes LONGBLOB NOT NULL, created_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (chunk_hash, model_name, dimensions)
);
