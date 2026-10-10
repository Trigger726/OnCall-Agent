CREATE TABLE oncall_schedule_member (
    schedule_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    active BOOLEAN NOT NULL,
    can_respond BOOLEAN NOT NULL,
    can_manage BOOLEAN NOT NULL,
    version INT NOT NULL DEFAULT 0,
    origin VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (schedule_id, user_id),
    CONSTRAINT fk_plan_member_schedule FOREIGN KEY (schedule_id) REFERENCES oncall_schedule(id),
    CONSTRAINT fk_plan_member_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT ck_plan_member_version CHECK (version >= 0),
    CONSTRAINT ck_plan_member_permissions CHECK (active = FALSE OR can_respond = TRUE OR can_manage = TRUE),
    CONSTRAINT ck_plan_member_origin CHECK (origin IN ('MIGRATED_GLOBAL_V38','EXPLICIT'))
);
CREATE INDEX idx_plan_member_user ON oncall_schedule_member(user_id, active, schedule_id);

-- One-time compatibility only. No trigger or future-role-based auto-enrolment.
INSERT INTO oncall_schedule_member(schedule_id,user_id,active,can_respond,can_manage,origin)
SELECT s.id,u.id,TRUE,TRUE,CASE WHEN u.role_code IN ('ADMIN','OPS_MANAGER') THEN TRUE ELSE FALSE END,'MIGRATED_GLOBAL_V38'
FROM oncall_schedule s CROSS JOIN sys_user u
WHERE u.status='ACTIVE' AND u.role_code IN ('ADMIN','OPS_MANAGER','ON_CALL');

CREATE TABLE oncall_schedule_member_operation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    operation_key VARCHAR(36) NOT NULL,
    expected_version INT NULL,
    result_version INT NOT NULL,
    active BOOLEAN NOT NULL,
    can_respond BOOLEAN NOT NULL,
    can_manage BOOLEAN NOT NULL,
    reason VARCHAR(500) NOT NULL,
    committed_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_member_operation_member FOREIGN KEY (schedule_id,user_id) REFERENCES oncall_schedule_member(schedule_id,user_id),
    CONSTRAINT fk_member_operation_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id),
    CONSTRAINT uq_member_operation_key UNIQUE (actor_id,operation_key),
    CONSTRAINT ck_member_operation_versions CHECK ((expected_version IS NULL OR expected_version >= 0) AND result_version >= 0)
);
