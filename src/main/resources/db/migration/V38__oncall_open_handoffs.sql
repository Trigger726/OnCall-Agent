CREATE TABLE oncall_open_handoff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id BIGINT NOT NULL,
    source_shift_id BIGINT NOT NULL,
    source_version INT NOT NULL,
    requester_id BIGINT NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    closed_at TIMESTAMP(6) NULL,
    claimed_by BIGINT NULL,
    replacement_shift_id BIGINT NULL,
    CONSTRAINT fk_open_handoff_schedule FOREIGN KEY (schedule_id) REFERENCES oncall_schedule(id),
    CONSTRAINT fk_open_handoff_source FOREIGN KEY (source_shift_id) REFERENCES oncall_shift(id),
    CONSTRAINT fk_open_handoff_requester FOREIGN KEY (requester_id) REFERENCES sys_user(id),
    CONSTRAINT fk_open_handoff_claimant FOREIGN KEY (claimed_by) REFERENCES sys_user(id),
    CONSTRAINT fk_open_handoff_replacement FOREIGN KEY (replacement_shift_id) REFERENCES oncall_shift(id),
    CONSTRAINT uq_open_handoff_request UNIQUE (requester_id, request_key),
    CONSTRAINT uq_open_handoff_replacement UNIQUE (replacement_shift_id),
    CONSTRAINT ck_open_handoff_versions CHECK (source_version >= 0 AND version >= 0),
    CONSTRAINT ck_open_handoff_window CHECK (starts_at < ends_at),
    CONSTRAINT ck_open_handoff_status CHECK (status IN ('OPEN','CLAIMED','WITHDRAWN'))
);
CREATE INDEX idx_open_handoff_inbox ON oncall_open_handoff(status, schedule_id, id);

CREATE TABLE oncall_open_handoff_operation (
    handoff_id BIGINT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    operation_key VARCHAR(36) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    captured_version INT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    committed_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_open_operation_handoff FOREIGN KEY (handoff_id) REFERENCES oncall_open_handoff(id),
    CONSTRAINT fk_open_operation_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id),
    CONSTRAINT uq_open_operation_key UNIQUE (actor_id, operation_key),
    CONSTRAINT ck_open_operation_version CHECK (captured_version >= 0),
    CONSTRAINT ck_open_operation_kind CHECK (operation IN ('CLAIM','WITHDRAW'))
);
