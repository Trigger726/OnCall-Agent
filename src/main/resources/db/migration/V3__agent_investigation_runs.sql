CREATE TABLE agent_investigation_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    trigger_source VARCHAR(32) NOT NULL,
    plan_summary TEXT NOT NULL,
    conclusion TEXT,
    report_id BIGINT,
    created_by BIGINT,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    duration_ms BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_run_incident FOREIGN KEY (incident_id) REFERENCES incident(id),
    CONSTRAINT fk_agent_run_report FOREIGN KEY (report_id) REFERENCES investigation_report(id),
    CONSTRAINT fk_agent_run_creator FOREIGN KEY (created_by) REFERENCES sys_user(id)
);

CREATE TABLE agent_investigation_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    phase VARCHAR(20) NOT NULL,
    tool_name VARCHAR(64),
    status VARCHAR(24) NOT NULL,
    title VARCHAR(160) NOT NULL,
    input_json TEXT,
    output_summary TEXT NOT NULL,
    evidence_json TEXT NOT NULL,
    error_message VARCHAR(1000),
    duration_ms BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_step_sequence UNIQUE (run_id, sequence_no),
    CONSTRAINT fk_agent_step_run FOREIGN KEY (run_id) REFERENCES agent_investigation_run(id) ON DELETE CASCADE
);

CREATE INDEX idx_agent_run_incident ON agent_investigation_run(incident_id, created_at);
CREATE INDEX idx_agent_step_run ON agent_investigation_step(run_id, sequence_no);
