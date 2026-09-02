CREATE TABLE agent_investigation_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    phase VARCHAR(20),
    tool_name VARCHAR(64),
    status VARCHAR(24),
    payload_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_event_sequence UNIQUE (run_id, sequence_no),
    CONSTRAINT fk_agent_event_run FOREIGN KEY (run_id)
        REFERENCES agent_investigation_run(id) ON DELETE CASCADE
);

CREATE INDEX idx_agent_event_run_cursor
    ON agent_investigation_event(run_id, id);

CREATE TABLE remediation_proposal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    change_id BIGINT,
    target_resource_id BIGINT NOT NULL,
    action_type VARCHAR(40) NOT NULL,
    risk_level VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    evidence_ref VARCHAR(160) NOT NULL,
    requested_by BIGINT NOT NULL,
    reviewed_by BIGINT,
    review_comment VARCHAR(500),
    version INT NOT NULL DEFAULT 1,
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_remediation_proposal_run UNIQUE (run_id),
    CONSTRAINT fk_remediation_incident FOREIGN KEY (incident_id) REFERENCES incident(id),
    CONSTRAINT fk_remediation_run FOREIGN KEY (run_id) REFERENCES agent_investigation_run(id),
    CONSTRAINT fk_remediation_change FOREIGN KEY (change_id) REFERENCES change_record(id),
    CONSTRAINT fk_remediation_resource FOREIGN KEY (target_resource_id) REFERENCES cmdb_resource(id),
    CONSTRAINT fk_remediation_requester FOREIGN KEY (requested_by) REFERENCES sys_user(id),
    CONSTRAINT fk_remediation_reviewer FOREIGN KEY (reviewed_by) REFERENCES sys_user(id)
);

CREATE INDEX idx_remediation_incident_status
    ON remediation_proposal(incident_id, status, created_at);
