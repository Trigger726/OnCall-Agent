ALTER TABLE oncall_shift ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE oncall_shift ADD COLUMN note VARCHAR(500) NOT NULL DEFAULT '';
ALTER TABLE oncall_shift ADD COLUMN cancelled_at TIMESTAMP NULL;
ALTER TABLE oncall_shift ADD COLUMN cancellation_reason VARCHAR(500) NULL;

CREATE INDEX idx_oncall_shift_window ON oncall_shift(schedule_id, cancelled_at, starts_at, ends_at);
