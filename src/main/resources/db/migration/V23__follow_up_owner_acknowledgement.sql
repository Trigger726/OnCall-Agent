ALTER TABLE postmortem_follow_up ADD COLUMN acknowledged_by BIGINT;
ALTER TABLE postmortem_follow_up ADD COLUMN acknowledged_at TIMESTAMP;
ALTER TABLE postmortem_follow_up ADD CONSTRAINT fk_postmortem_follow_up_acknowledger
    FOREIGN KEY (acknowledged_by) REFERENCES sys_user(id);
