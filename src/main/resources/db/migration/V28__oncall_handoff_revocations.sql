CREATE TABLE oncall_handoff_revocation (
    handoff_id BIGINT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    operation_key VARCHAR(36) NOT NULL,
    handoff_version INT NOT NULL,
    replacement_version INT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    revoked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_handoff_revocation_request FOREIGN KEY (handoff_id) REFERENCES oncall_handoff(id),
    CONSTRAINT fk_handoff_revocation_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id),
    CONSTRAINT uq_handoff_revocation_key UNIQUE (actor_id, operation_key)
);
