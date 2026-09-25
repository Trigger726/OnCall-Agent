ALTER TABLE escalation_policy ADD COLUMN severity VARCHAR(8);
UPDATE escalation_policy SET severity = 'P1' WHERE id = 1;

CREATE TABLE incident_escalation_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    policy_id BIGINT NOT NULL,
    step_id BIGINT NOT NULL,
    due_at TIMESTAMP NOT NULL,
    status VARCHAR(24) NOT NULL,
    recipient VARCHAR(128),
    detail VARCHAR(255),
    executed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_incident_escalation_step UNIQUE (incident_id, step_id),
    CONSTRAINT fk_incident_escalation_incident FOREIGN KEY (incident_id) REFERENCES incident(id),
    CONSTRAINT fk_incident_escalation_policy FOREIGN KEY (policy_id) REFERENCES escalation_policy(id),
    CONSTRAINT fk_incident_escalation_step FOREIGN KEY (step_id) REFERENCES escalation_step(id)
);

CREATE INDEX idx_incident_escalation_executed ON incident_escalation_event(executed_at, id);
