UPDATE incident
SET resolved_at = (
    SELECT MIN(timeline.created_at)
    FROM incident_timeline timeline
    WHERE timeline.incident_id = incident.id
      AND timeline.to_status = 'RESOLVED'
)
WHERE resolved_at IS NULL
  AND EXISTS (
    SELECT 1
    FROM incident_timeline timeline
    WHERE timeline.incident_id = incident.id
      AND timeline.to_status = 'RESOLVED'
);

CREATE TABLE postmortem_follow_up_escalation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    follow_up_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    due_date_snapshot DATE NOT NULL,
    detected_as_of DATE NOT NULL,
    first_detected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    resolved_at TIMESTAMP,
    resolved_by BIGINT,
    CONSTRAINT uq_follow_up_escalation UNIQUE (follow_up_id),
    CONSTRAINT ck_follow_up_escalation_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT fk_follow_up_escalation_item FOREIGN KEY (follow_up_id) REFERENCES postmortem_follow_up(id),
    CONSTRAINT fk_follow_up_escalation_creator FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT fk_follow_up_escalation_resolver FOREIGN KEY (resolved_by) REFERENCES sys_user(id)
);

CREATE INDEX idx_follow_up_escalation_status
    ON postmortem_follow_up_escalation(status, first_detected_at);
