ALTER TABLE alert_ingest_rejection
    ADD COLUMN payload_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE alert_ingest_rejection
    ADD COLUMN auto_replay_count INT NOT NULL DEFAULT 0;

ALTER TABLE alert_ingest_rejection
    ADD COLUMN next_auto_replay_at TIMESTAMP;

ALTER TABLE alert_ingest_rejection
    ADD COLUMN auto_replay_exhausted_at TIMESTAMP;

ALTER TABLE alert_ingest_rejection
    ADD COLUMN purged_at TIMESTAMP;

ALTER TABLE alert_ingest_rejection
    ADD CONSTRAINT ck_alert_ingest_rejection_payload_status
        CHECK (payload_status IN ('ACTIVE', 'PURGED'));

ALTER TABLE alert_ingest_rejection
    ADD CONSTRAINT ck_alert_ingest_rejection_auto_count
        CHECK (auto_replay_count >= 0);

UPDATE alert_ingest_rejection
SET next_auto_replay_at = last_received_at
WHERE status = 'OPEN' AND payload_status = 'ACTIVE' AND error_code = 'RESOURCE_NOT_FOUND';

CREATE INDEX idx_alert_ingest_rejection_auto_replay
    ON alert_ingest_rejection(status, payload_status, next_auto_replay_at);

CREATE INDEX idx_alert_ingest_rejection_payload_retention
    ON alert_ingest_rejection(payload_status, last_received_at);
