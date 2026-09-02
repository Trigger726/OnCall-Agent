ALTER TABLE agent_investigation_run
    ADD COLUMN idempotency_key VARCHAR(128);

ALTER TABLE agent_investigation_run
    ADD COLUMN deadline_at TIMESTAMP;

ALTER TABLE agent_investigation_run
    ADD COLUMN termination_kind VARCHAR(16);

ALTER TABLE agent_investigation_run
    ADD COLUMN termination_requested_at TIMESTAMP;

ALTER TABLE agent_investigation_run
    ADD COLUMN termination_requested_by BIGINT;

ALTER TABLE agent_investigation_run
    ADD COLUMN termination_reason VARCHAR(500);

ALTER TABLE agent_investigation_run
    ADD COLUMN next_event_sequence INT NOT NULL DEFAULT 1;

UPDATE agent_investigation_run run
SET next_event_sequence = (
    SELECT COALESCE(MAX(event.sequence_no), 0) + 1
    FROM agent_investigation_event event
    WHERE event.run_id = run.id
);

ALTER TABLE agent_investigation_run
    ADD CONSTRAINT uq_agent_run_idempotency UNIQUE (incident_id, idempotency_key);

ALTER TABLE agent_investigation_run
    ADD CONSTRAINT fk_agent_run_termination_requester
        FOREIGN KEY (termination_requested_by) REFERENCES sys_user(id);

CREATE INDEX idx_agent_run_status_deadline
    ON agent_investigation_run(status, deadline_at);
