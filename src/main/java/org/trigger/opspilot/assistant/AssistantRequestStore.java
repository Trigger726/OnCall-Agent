package org.trigger.opspilot.assistant;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.trigger.opspilot.common.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Durable accepted-question identity. Queue admission itself does not write a request or a USER message. */
@Component
public class AssistantRequestStore {
    private final JdbcClient jdbc;
    public AssistantRequestStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    public static String keyHash(String raw) {
        if (raw == null) return null;
        String key = raw.trim();
        if (key.isEmpty() || key.length() > 128 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSISTANT_IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key 需为1至128个字母、数字或._:-字符");
        }
        return hash(key); // Hashing also avoids MySQL's case-insensitive key collation changing identity.
    }

    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }

    Entry find(long sessionId, String keyHash, boolean lock) {
        jdbc.sql("""
                UPDATE assistant_request SET status='TIMED_OUT',updated_at=CURRENT_TIMESTAMP
                WHERE session_id=:session AND request_key_hash=:key AND status='RUNNING' AND deadline_epoch_ms<=:now
                """).param("session", sessionId).param("key", keyHash).param("now", System.currentTimeMillis()).update();
        return jdbc.sql("""
                SELECT id,content_hash,attempt_id,status,question_message_id,answer_message_id,deadline_epoch_ms
                FROM assistant_request WHERE session_id=:session AND request_key_hash=:key
                """ + (lock ? " FOR UPDATE" : ""))
                .param("session", sessionId).param("key", keyHash)
                .query((rs, row) -> new Entry(rs.getLong("id"), rs.getString("content_hash"), rs.getString("attempt_id"),
                        rs.getString("status"), rs.getObject("question_message_id", Long.class),
                        rs.getObject("answer_message_id", Long.class), rs.getLong("deadline_epoch_ms")))
                .optional().orElse(null);
    }

    void prepare(long sessionId, String question, long questionId, Identity identity) {
        jdbc.sql("""
                INSERT INTO assistant_request(session_id,request_key_hash,content_hash,attempt_id,status,question_message_id,deadline_epoch_ms)
                VALUES (:session,:key,:content,:attempt,'RUNNING',:question,:deadline)
                """).param("session", sessionId).param("key", identity.keyHash()).param("content", hash(question))
                .param("attempt", identity.attemptId()).param("question", questionId).param("deadline", identity.deadlineEpochMs()).update();
    }

    void requireRunning(long sessionId, Identity identity) {
        Entry entry = find(sessionId, identity.keyHash(), true);
        if (entry == null || !identity.attemptId().equals(entry.attemptId())) {
            throw new ApiException(HttpStatus.CONFLICT, "ASSISTANT_REQUEST_STOPPED", "原请求已停止，请核对会话后重新提问");
        }
        if (!"RUNNING".equals(entry.status())) throw stopped(entry);
    }

    void complete(long sessionId, Identity identity, long answerId) {
        int changed = jdbc.sql("""
                UPDATE assistant_request SET status='COMPLETED',answer_message_id=:answer,updated_at=CURRENT_TIMESTAMP
                WHERE session_id=:session AND request_key_hash=:key AND attempt_id=:attempt AND status='RUNNING'
                """).param("answer", answerId).param("session", sessionId).param("key", identity.keyHash())
                .param("attempt", identity.attemptId()).update();
        if (changed != 1) throw new ApiException(HttpStatus.CONFLICT, "ASSISTANT_REQUEST_STOPPED", "原请求已停止，请重新提问");
    }

    void stop(long sessionId, String keyHash, String attemptId, String status) {
        jdbc.sql("""
                UPDATE assistant_request SET status=:status,updated_at=CURRENT_TIMESTAMP
                WHERE session_id=:session AND request_key_hash=:key AND attempt_id=:attempt AND status='RUNNING'
                """).param("status", status).param("session", sessionId).param("key", keyHash).param("attempt", attemptId).update();
    }

    void supersede(long sessionId) {
        jdbc.sql("UPDATE assistant_request SET status='SUPERSEDED',updated_at=CURRENT_TIMESTAMP WHERE session_id=:session")
                .param("session", sessionId).update();
    }

    boolean replayedByDifferentAttempt(long sessionId, String keyHash, String attemptId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM assistant_request WHERE session_id=:session AND request_key_hash=:key
                AND attempt_id<>:attempt AND status='COMPLETED'
                """).param("session", sessionId).param("key", keyHash).param("attempt", attemptId).query(Long.class).single() == 1;
    }

    static ApiException stopped(Entry entry) {
        String code = "TIMED_OUT".equals(entry.status()) ? "ASSISTANT_REQUEST_TIMED_OUT"
                : "SUPERSEDED".equals(entry.status()) ? "ASSISTANT_REQUEST_SUPERSEDED" : "ASSISTANT_REQUEST_STOPPED";
        return new ApiException(HttpStatus.CONFLICT, code, "原请求已停止或已清空，请核对会话后使用新键提问");
    }

    public record Identity(String keyHash, String attemptId, long deadlineEpochMs) { }
    record Entry(long id, String contentHash, String attemptId, String status, Long questionId, Long answerId, long deadlineEpochMs) { }
    public record View(long id, String status, Long questionMessageId, Long answerMessageId, long deadlineEpochMs) { }
}
