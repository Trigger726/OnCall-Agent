CREATE TABLE alert_ingest_rejection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source VARCHAR(32) NOT NULL,
    rejection_key CHAR(64) NOT NULL,
    external_event_id VARCHAR(128),
    receiver VARCHAR(120),
    delivery_group_key VARCHAR(512),
    error_code VARCHAR(80) NOT NULL,
    error_message VARCHAR(500) NOT NULL,
    payload_json TEXT NOT NULL,
    alert_name VARCHAR(240),
    resource_code VARCHAR(120),
    severity VARCHAR(32),
    alert_status VARCHAR(16),
    redacted_fields INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    delivery_count INT NOT NULL DEFAULT 1,
    replay_count INT NOT NULL DEFAULT 0,
    replay_token VARCHAR(64),
    replay_lease_until TIMESTAMP,
    last_replay_error_code VARCHAR(80),
    last_replay_error_message VARCHAR(500),
    resolved_alert_id BIGINT,
    resolved_incident_id BIGINT,
    resolved_by BIGINT,
    version INT NOT NULL DEFAULT 0,
    first_received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_replayed_at TIMESTAMP,
    resolved_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_alert_ingest_rejection UNIQUE (source, rejection_key),
    CONSTRAINT ck_alert_ingest_rejection_status CHECK (status IN ('OPEN', 'SUCCEEDED')),
    CONSTRAINT ck_alert_ingest_rejection_counts CHECK (
        delivery_count > 0 AND replay_count >= 0 AND redacted_fields >= 0
    ),
    CONSTRAINT fk_alert_ingest_rejection_alert FOREIGN KEY (resolved_alert_id) REFERENCES alert_event(id),
    CONSTRAINT fk_alert_ingest_rejection_incident FOREIGN KEY (resolved_incident_id) REFERENCES incident(id),
    CONSTRAINT fk_alert_ingest_rejection_actor FOREIGN KEY (resolved_by) REFERENCES sys_user(id)
);

CREATE INDEX idx_alert_ingest_rejection_status
    ON alert_ingest_rejection(status, last_received_at);
CREATE INDEX idx_alert_ingest_rejection_lease
    ON alert_ingest_rejection(status, replay_lease_until);
