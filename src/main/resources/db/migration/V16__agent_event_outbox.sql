CREATE TABLE agent_event_outbox (
    event_id BIGINT PRIMARY KEY,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token VARCHAR(36),
    lease_until TIMESTAMP,
    delivered_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_outbox_event FOREIGN KEY (event_id)
        REFERENCES agent_investigation_event(id) ON DELETE CASCADE
);

CREATE INDEX idx_agent_outbox_pending ON agent_event_outbox(status, next_attempt_at, event_id);
CREATE INDEX idx_agent_outbox_lease ON agent_event_outbox(status, lease_until);
