-- Immutable publication observations only; no delivery receipt or retroactive broadcast.
CREATE TABLE oncall_open_handoff_publication (
    handoff_id BIGINT PRIMARY KEY,
    event_version INT NOT NULL,
    captured_at TIMESTAMP(6) NOT NULL,
    snapshot_json TEXT NOT NULL,
    CONSTRAINT fk_open_publication_request FOREIGN KEY (handoff_id) REFERENCES oncall_open_handoff(id),
    CONSTRAINT ck_open_publication_version CHECK (event_version = 0)
);
