ALTER TABLE oncall_open_handoff_notification ADD COLUMN cycle_start_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE oncall_open_handoff_notification ADD CONSTRAINT ck_open_notification_cycle CHECK (cycle_start_attempts>=0 AND cycle_start_attempts<=attempts);

CREATE TABLE oncall_open_notification_redrive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    notification_id BIGINT NOT NULL,
    handoff_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    operation_key VARCHAR(36) NOT NULL,
    captured_version INT NOT NULL,
    queued_version INT NOT NULL,
    prior_attempts INT NOT NULL,
    prior_http_status INT,
    prior_error_code VARCHAR(64),
    delivery_key VARCHAR(36) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    accepted_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_open_redrive_notification FOREIGN KEY (notification_id) REFERENCES oncall_open_handoff_notification(id),
    CONSTRAINT fk_open_redrive_handoff FOREIGN KEY (handoff_id) REFERENCES oncall_open_handoff(id),
    CONSTRAINT fk_open_redrive_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id),
    CONSTRAINT uq_open_redrive_operation UNIQUE (actor_id,operation_key),
    CONSTRAINT ck_open_redrive_versions CHECK (captured_version>=0 AND queued_version=captured_version+1 AND prior_attempts>=0)
);
