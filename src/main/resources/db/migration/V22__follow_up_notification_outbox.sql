CREATE TABLE postmortem_follow_up_notification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    escalation_id BIGINT NOT NULL,
    title_snapshot VARCHAR(240) NOT NULL,
    owner_name_snapshot VARCHAR(64) NOT NULL,
    incident_code_snapshot VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token VARCHAR(36),
    lease_until TIMESTAMP,
    last_http_status INT,
    last_error_code VARCHAR(64),
    delivered_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_follow_up_notification_escalation UNIQUE (escalation_id),
    CONSTRAINT ck_follow_up_notification_status
        CHECK (status IN ('PENDING', 'CLAIMED', 'DELIVERED', 'FAILED', 'SKIPPED')),
    CONSTRAINT fk_follow_up_notification_escalation
        FOREIGN KEY (escalation_id) REFERENCES postmortem_follow_up_escalation(id)
);

CREATE INDEX idx_follow_up_notification_due
    ON postmortem_follow_up_notification(status, next_attempt_at, lease_until);
