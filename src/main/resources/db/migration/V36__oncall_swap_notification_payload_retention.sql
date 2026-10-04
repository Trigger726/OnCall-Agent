ALTER TABLE oncall_swap_notification ADD COLUMN payload_expires_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
ALTER TABLE oncall_swap_notification ADD COLUMN payload_erased_at TIMESTAMP(6);
UPDATE oncall_swap_notification SET payload_expires_at=TIMESTAMPADD(DAY,30,created_at);
CREATE INDEX idx_swap_notification_payload_retention ON oncall_swap_notification(payload_erased_at,payload_expires_at,id);
