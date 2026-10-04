CREATE TABLE oncall_swap_revocation (
    swap_id BIGINT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    operation_key VARCHAR(36) NOT NULL,
    swap_version INT NOT NULL,
    first_replacement_version INT NOT NULL,
    second_replacement_version INT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    revoked_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_swap_revocation_request FOREIGN KEY (swap_id) REFERENCES oncall_shift_swap(id),
    CONSTRAINT fk_swap_revocation_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id),
    CONSTRAINT uq_swap_revocation_key UNIQUE (actor_id, operation_key),
    CONSTRAINT ck_swap_revocation_versions CHECK (swap_version>=0 AND first_replacement_version>=0 AND second_replacement_version>=0)
);
