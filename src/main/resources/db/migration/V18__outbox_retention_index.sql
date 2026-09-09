CREATE INDEX idx_agent_outbox_retention ON agent_event_outbox(status, delivered_at, event_id);
