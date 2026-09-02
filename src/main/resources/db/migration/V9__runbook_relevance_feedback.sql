CREATE TABLE runbook_retrieval_query (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    query_text VARCHAR(500) NOT NULL,
    query_hash VARCHAR(64) NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    requested_mode VARCHAR(16) NOT NULL,
    actual_engine VARCHAR(40) NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    semantic_status VARCHAR(32) NOT NULL,
    semantic_coverage DECIMAL(8, 6) NOT NULL,
    candidate_chunk_count INT NOT NULL,
    top_k INT NOT NULL,
    latency_ms BIGINT NOT NULL,
    results_json TEXT NOT NULL,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_runbook_query_creator FOREIGN KEY (created_by) REFERENCES sys_user(id)
);

CREATE INDEX idx_runbook_query_created ON runbook_retrieval_query(created_at, actual_engine);
CREATE INDEX idx_runbook_query_hash ON runbook_retrieval_query(query_hash, created_at);

CREATE TABLE runbook_relevance_judgment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    search_id BIGINT NOT NULL,
    document_stable_key VARCHAR(80) NOT NULL,
    relevance_grade INT NOT NULL,
    comment VARCHAR(500),
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    version_no INT NOT NULL DEFAULT 0,
    judged_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by BIGINT,
    reviewed_at TIMESTAMP,
    review_note VARCHAR(500),
    CONSTRAINT uq_runbook_judgment_actor UNIQUE (search_id, document_stable_key, judged_by),
    CONSTRAINT ck_runbook_judgment_grade CHECK (relevance_grade BETWEEN 0 AND 3),
    CONSTRAINT ck_runbook_judgment_status CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT fk_runbook_judgment_search FOREIGN KEY (search_id) REFERENCES runbook_retrieval_query(id),
    CONSTRAINT fk_runbook_judgment_actor FOREIGN KEY (judged_by) REFERENCES sys_user(id),
    CONSTRAINT fk_runbook_judgment_reviewer FOREIGN KEY (reviewed_by) REFERENCES sys_user(id)
);

CREATE INDEX idx_runbook_judgment_review ON runbook_relevance_judgment(review_status, created_at);

ALTER TABLE runbook_retrieval_eval_case
    ADD source_type VARCHAR(24) NOT NULL DEFAULT 'SEED';
ALTER TABLE runbook_retrieval_eval_case ADD judgment_id BIGINT;
ALTER TABLE runbook_retrieval_eval_case ADD reviewed_by BIGINT;
ALTER TABLE runbook_retrieval_eval_case
    ADD CONSTRAINT fk_runbook_eval_judgment FOREIGN KEY (judgment_id) REFERENCES runbook_relevance_judgment(id);
ALTER TABLE runbook_retrieval_eval_case
    ADD CONSTRAINT fk_runbook_eval_reviewer FOREIGN KEY (reviewed_by) REFERENCES sys_user(id);
CREATE UNIQUE INDEX uq_runbook_eval_judgment ON runbook_retrieval_eval_case(judgment_id);
