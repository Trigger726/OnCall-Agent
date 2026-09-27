ALTER TABLE runbook_document MODIFY COLUMN published_at TIMESTAMP NULL DEFAULT NULL;
ALTER TABLE runbook_document ADD COLUMN review_version INT NOT NULL DEFAULT 0;
ALTER TABLE runbook_document ADD COLUMN base_published_version INT NOT NULL DEFAULT 0;
ALTER TABLE runbook_document ADD COLUMN submission_hash VARCHAR(64);
ALTER TABLE runbook_document ADD COLUMN reviewed_by BIGINT;
ALTER TABLE runbook_document ADD COLUMN reviewed_at TIMESTAMP NULL;
ALTER TABLE runbook_document ADD COLUMN review_note VARCHAR(500);
ALTER TABLE runbook_document ADD COLUMN decision_key VARCHAR(36);
ALTER TABLE runbook_document ADD COLUMN decision_hash VARCHAR(64);
ALTER TABLE runbook_document ADD COLUMN decision VARCHAR(16);
ALTER TABLE runbook_document ADD CONSTRAINT uq_runbook_decision_key UNIQUE (decision_key);
ALTER TABLE runbook_document ADD CONSTRAINT fk_runbook_reviewer FOREIGN KEY (reviewed_by) REFERENCES sys_user(id);
ALTER TABLE runbook_document ADD CONSTRAINT ck_runbook_review_version CHECK (review_version >= 0 AND base_published_version >= 0);
ALTER TABLE runbook_document ADD CONSTRAINT ck_runbook_publication_status CHECK
    (status IN ('PENDING_REVIEW', 'PUBLISHED', 'SUPERSEDED', 'REJECTED', 'WITHDRAWN'));

-- One serialization row per logical document; no global lock and no content rewrite.
CREATE TABLE runbook_publication_lock (stable_key VARCHAR(80) PRIMARY KEY);
INSERT INTO runbook_publication_lock(stable_key) SELECT DISTINCT stable_key FROM runbook_document;
CREATE INDEX idx_runbook_review_queue ON runbook_document(status, created_at, id);
