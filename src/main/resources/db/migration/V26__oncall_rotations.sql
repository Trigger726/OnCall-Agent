CREATE TABLE oncall_rotation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    anchor_at TIMESTAMP NOT NULL,
    shift_minutes INT NOT NULL,
    member_ids_json TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version INT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_scan_at TIMESTAMP NULL,
    last_warning VARCHAR(128) NULL,
    state_reason VARCHAR(500) NOT NULL DEFAULT '',
    CONSTRAINT fk_rotation_schedule FOREIGN KEY (schedule_id) REFERENCES oncall_schedule(id),
    CONSTRAINT fk_rotation_creator FOREIGN KEY (created_by) REFERENCES sys_user(id)
);
CREATE INDEX idx_rotation_scan ON oncall_rotation(active, last_scan_at, id);

ALTER TABLE oncall_shift ADD COLUMN rotation_id BIGINT NULL;
ALTER TABLE oncall_shift ADD COLUMN rotation_slot BIGINT NULL;
ALTER TABLE oncall_shift ADD CONSTRAINT fk_shift_rotation FOREIGN KEY (rotation_id) REFERENCES oncall_rotation(id);
CREATE UNIQUE INDEX uq_rotation_generated_shift ON oncall_shift(rotation_id, rotation_slot);

CREATE TABLE oncall_rotation_slot (
    rotation_id BIGINT NOT NULL,
    slot_index BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    status VARCHAR(32) NOT NULL,
    detail VARCHAR(128) NOT NULL,
    shift_id BIGINT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rotation_id, slot_index),
    CONSTRAINT fk_slot_rotation FOREIGN KEY (rotation_id) REFERENCES oncall_rotation(id),
    CONSTRAINT fk_slot_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_slot_shift FOREIGN KEY (shift_id) REFERENCES oncall_shift(id)
);
