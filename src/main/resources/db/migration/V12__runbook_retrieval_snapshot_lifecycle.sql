ALTER TABLE runbook_retrieval_query
    ADD snapshot_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE runbook_retrieval_query ADD redacted_fields INT NOT NULL DEFAULT 0;
ALTER TABLE runbook_retrieval_query ADD purged_at TIMESTAMP;

ALTER TABLE runbook_retrieval_query
    ADD CONSTRAINT ck_runbook_query_snapshot_status
        CHECK (snapshot_status IN ('ACTIVE', 'PURGED'));
ALTER TABLE runbook_retrieval_query
    ADD CONSTRAINT ck_runbook_query_redacted_fields
        CHECK (redacted_fields >= 0);

CREATE INDEX idx_runbook_query_retention
    ON runbook_retrieval_query(snapshot_status, created_at);
