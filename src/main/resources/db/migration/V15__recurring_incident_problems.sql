CREATE TABLE problem_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    problem_code VARCHAR(64) NOT NULL,
    recurrence_key VARCHAR(192) NOT NULL,
    service_resource_id BIGINT NOT NULL,
    alert_fingerprint VARCHAR(128) NOT NULL,
    title VARCHAR(240) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    root_cause TEXT NOT NULL,
    workaround TEXT NOT NULL,
    resolution_summary TEXT NOT NULL,
    owner_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    resolved_by BIGINT,
    resolved_at TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_problem_code UNIQUE (problem_code),
    CONSTRAINT uq_problem_recurrence UNIQUE (recurrence_key),
    CONSTRAINT ck_problem_status CHECK (status IN ('OPEN', 'KNOWN_ERROR', 'RESOLVED')),
    CONSTRAINT fk_problem_service FOREIGN KEY (service_resource_id) REFERENCES cmdb_resource(id),
    CONSTRAINT fk_problem_owner FOREIGN KEY (owner_id) REFERENCES sys_user(id),
    CONSTRAINT fk_problem_creator FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT fk_problem_resolver FOREIGN KEY (resolved_by) REFERENCES sys_user(id)
);

CREATE INDEX idx_problem_status_owner
    ON problem_record(status, owner_id, updated_at);

CREATE TABLE problem_incident_link (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    problem_id BIGINT NOT NULL,
    incident_id BIGINT NOT NULL,
    link_reason VARCHAR(64) NOT NULL,
    linked_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_problem_incident UNIQUE (problem_id, incident_id),
    CONSTRAINT fk_problem_link_problem FOREIGN KEY (problem_id) REFERENCES problem_record(id),
    CONSTRAINT fk_problem_link_incident FOREIGN KEY (incident_id) REFERENCES incident(id),
    CONSTRAINT fk_problem_link_actor FOREIGN KEY (linked_by) REFERENCES sys_user(id)
);

CREATE INDEX idx_problem_link_incident
    ON problem_incident_link(incident_id, problem_id);
