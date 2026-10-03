CREATE TABLE assistant_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    request_key_hash CHAR(64) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    attempt_id CHAR(36) NOT NULL,
    status VARCHAR(24) NOT NULL,
    question_message_id BIGINT,
    answer_message_id BIGINT,
    deadline_epoch_ms BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_assistant_request_key UNIQUE(session_id, request_key_hash),
    CONSTRAINT fk_assistant_request_session FOREIGN KEY(session_id) REFERENCES assistant_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_request_question FOREIGN KEY(question_message_id) REFERENCES assistant_message(id) ON DELETE SET NULL,
    CONSTRAINT fk_assistant_request_answer FOREIGN KEY(answer_message_id) REFERENCES assistant_message(id) ON DELETE SET NULL,
    CONSTRAINT ck_assistant_request_status CHECK(status IN ('RUNNING', 'COMPLETED', 'FAILED', 'TIMED_OUT', 'REVOKED', 'SUPERSEDED'))
);
