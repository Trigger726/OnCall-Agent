CREATE TABLE incident_postmortem (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    summary TEXT NOT NULL,
    customer_impact TEXT NOT NULL,
    root_cause TEXT NOT NULL,
    contributing_factors TEXT NOT NULL,
    lessons_learned TEXT NOT NULL,
    timeline_snapshot_json LONGTEXT NOT NULL,
    evidence_refs_json LONGTEXT NOT NULL,
    created_by BIGINT NOT NULL,
    submitted_by BIGINT,
    reviewed_by BIGINT,
    review_comment VARCHAR(500),
    submitted_at TIMESTAMP,
    reviewed_at TIMESTAMP,
    published_at TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_incident_postmortem UNIQUE (incident_id),
    CONSTRAINT ck_postmortem_status CHECK (status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED')),
    CONSTRAINT fk_postmortem_incident FOREIGN KEY (incident_id) REFERENCES incident(id),
    CONSTRAINT fk_postmortem_creator FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT fk_postmortem_submitter FOREIGN KEY (submitted_by) REFERENCES sys_user(id),
    CONSTRAINT fk_postmortem_reviewer FOREIGN KEY (reviewed_by) REFERENCES sys_user(id)
);

CREATE INDEX idx_postmortem_status ON incident_postmortem(status, updated_at);

CREATE TABLE postmortem_follow_up (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    postmortem_id BIGINT NOT NULL,
    title VARCHAR(240) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    priority VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    owner_id BIGINT NOT NULL,
    due_date DATE NOT NULL,
    created_by BIGINT NOT NULL,
    completed_by BIGINT,
    completed_at TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_postmortem_follow_up_priority CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_postmortem_follow_up_status CHECK (status IN ('OPEN', 'DONE')),
    CONSTRAINT fk_postmortem_follow_up_parent FOREIGN KEY (postmortem_id) REFERENCES incident_postmortem(id),
    CONSTRAINT fk_postmortem_follow_up_owner FOREIGN KEY (owner_id) REFERENCES sys_user(id),
    CONSTRAINT fk_postmortem_follow_up_creator FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT fk_postmortem_follow_up_completer FOREIGN KEY (completed_by) REFERENCES sys_user(id)
);

CREATE INDEX idx_postmortem_follow_up_owner
    ON postmortem_follow_up(owner_id, status, due_date);
