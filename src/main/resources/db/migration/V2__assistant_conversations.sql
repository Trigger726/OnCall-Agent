CREATE TABLE assistant_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    incident_id BIGINT,
    title VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_assistant_session_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_assistant_session_incident FOREIGN KEY (incident_id) REFERENCES incident(id)
);

CREATE INDEX idx_assistant_session_owner ON assistant_session(owner_user_id, updated_at);
CREATE INDEX idx_assistant_session_incident ON assistant_session(incident_id, updated_at);

CREATE TABLE assistant_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    evidence_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_assistant_message_session FOREIGN KEY (session_id)
        REFERENCES assistant_session(id) ON DELETE CASCADE
);

CREATE INDEX idx_assistant_message_session ON assistant_message(session_id, id);
