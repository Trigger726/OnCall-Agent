package org.trigger.opspilot.runbook;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.observability.LogRedactor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class RunbookPublicationService {
    private static final Set<String> STATUSES = Set.of("PENDING_REVIEW", "PUBLISHED", "SUPERSEDED", "REJECTED", "WITHDRAWN");
    private final JdbcClient jdbc;
    private final RunbookService books;
    private final AuditService audit;
    private final LogRedactor redactor;

    public RunbookPublicationService(JdbcClient jdbc, RunbookService books, AuditService audit, LogRedactor redactor) {
        this.jdbc = jdbc;
        this.books = books;
        this.audit = audit;
        this.redactor = redactor;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    Queue list(String status) {
        if (status == null || !STATUSES.contains(status)) throw invalid("无效的审核状态");
        long total = jdbc.sql("SELECT COUNT(*) FROM runbook_document WHERE status=:status")
                .param("status", status).query(Long.class).single();
        List<QueueItem> items = jdbc.sql("""
                SELECT id, stable_key, version_no, title, status, created_by, created_at, review_version,
                       base_published_version FROM runbook_document WHERE status=:status
                ORDER BY created_at, id LIMIT 200
                """).param("status", status).query((rs, n) -> new QueueItem(rs.getLong("id"),
                rs.getString("stable_key"), rs.getInt("version_no"), rs.getString("title"), rs.getString("status"),
                rs.getObject("created_by", Long.class), rs.getObject("created_at", LocalDateTime.class),
                rs.getInt("review_version"), rs.getInt("base_published_version"))).list();
        return new Queue(items, total, total > items.size());
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    Review detail(long id) {
        State state = state(id, false);
        int currentVersion = jdbc.sql("SELECT version_no FROM runbook_document WHERE stable_key=:key AND status='PUBLISHED'")
                .param("key", state.stableKey()).query(Integer.class).optional().orElse(0);
        return new Review(books.documentById(id), state.reviewVersion(), state.baseVersion(), currentVersion,
                state.reviewedBy(), state.reviewedAt(), state.note(), state.decision());
    }

    @Transactional
    public Review decide(long id, int expectedVersion, String decision, String requestKey, String reason, Long actorId) {
        books.requireManager(actorId);
        if (expectedVersion < 0 || decision == null || !Set.of("APPROVE", "REJECT", "WITHDRAW").contains(decision)) throw invalid("无效的审核决定");
        try {
            if (!UUID.fromString(requestKey).toString().equals(requestKey)) throw invalid("请求键必须为标准 UUID");
        } catch (IllegalArgumentException | NullPointerException invalidKey) {
            throw invalid("请求键必须为标准 UUID");
        }
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) throw invalid("请填写 1–500 字的复核说明");
        String normalizedReason = reason.strip();
        String hash = RunbookService.sha256(id + "\n" + actorId + "\n" + expectedVersion + "\n" + decision + "\n" + normalizedReason);
        String stableKey = jdbc.sql("SELECT stable_key FROM runbook_document WHERE id=:id")
                .param("id", id).query(String.class).optional().orElseThrow(() -> missing());
        books.lockPublicationKey(stableKey);
        State state = state(id, true);
        if (requestKey.equals(state.key())) {
            if (!hash.equals(state.hash())) throw conflict("RUNBOOK_PUBLICATION_KEY_CONFLICT", "请求键已用于不同决定");
            return commandResult(id, stableKey); // A superseded approval remains superseded; replay never republishes it.
        }
        if (jdbc.sql("SELECT COUNT(*) FROM runbook_document WHERE decision_key=:key")
                .param("key", requestKey).query(Long.class).single() > 0) {
            throw conflict("RUNBOOK_PUBLICATION_KEY_CONFLICT", "请求键已用于其他手册");
        }
        if ("WITHDRAW".equals(decision)) {
            if (!Objects.equals(actorId, state.createdBy())) throw new ApiException(HttpStatus.FORBIDDEN,
                    "RUNBOOK_WITHDRAW_NOT_OWNER", "仅提交人可以撤回候选版本");
        } else if (Objects.equals(actorId, state.createdBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "RUNBOOK_SELF_REVIEW_FORBIDDEN", "提交人不能复核自己的版本");
        }
        if (!"PENDING_REVIEW".equals(state.status()) || state.reviewVersion() != expectedVersion) {
            throw conflict("RUNBOOK_PUBLICATION_VERSION_CONFLICT", "候选状态或版本已变化，请重新查看");
        }
        if ("APPROVE".equals(decision)) {
            if (books.currentPublishedVersion(stableKey) != state.baseVersion()) {
                throw conflict("RUNBOOK_PUBLICATION_BASELINE_CONFLICT", "已发布基线已变化，不能覆盖新版本；请重新提交候选");
            }
            jdbc.sql("UPDATE runbook_document SET status='SUPERSEDED' WHERE stable_key=:key AND status='PUBLISHED'")
                    .param("key", stableKey).update();
        }
        String status = switch (decision) { case "APPROVE" -> "PUBLISHED"; case "REJECT" -> "REJECTED"; default -> "WITHDRAWN"; };
        try {
            jdbc.sql("""
                    UPDATE runbook_document SET status=:status, review_version=review_version+1,
                      reviewed_by=:actor, reviewed_at=CURRENT_TIMESTAMP, review_note=:note,
                      decision=:decision, decision_key=:key, decision_hash=:hash,
                      published_at=CASE WHEN :decision='APPROVE' THEN CURRENT_TIMESTAMP ELSE NULL END
                    WHERE id=:id
                    """).param("status", status).param("actor", actorId).param("note", redactor.redact(normalizedReason))
                    .param("decision", decision).param("key", requestKey).param("hash", hash).param("id", id).update();
        } catch (DuplicateKeyException collision) {
            throw conflict("RUNBOOK_PUBLICATION_KEY_CONFLICT", "请求键已用于其他手册");
        }
        audit.recordAs(actorId, null, "RUNBOOK_PUBLICATION_" + decision, "RUNBOOK", id,
                "候选 v" + state.versionNo() + "，基线 v" + state.baseVersion() + "，决定 " + decision);
        return commandResult(id, stableKey);
    }

    private Review commandResult(long id, String stableKey) {
        // Current reads, not the earlier predicate snapshot from a repeatable-read transaction.
        State current = state(id, true);
        return new Review(books.documentById(id, true), current.reviewVersion(), current.baseVersion(),
                books.currentPublishedVersion(stableKey), current.reviewedBy(), current.reviewedAt(),
                current.note(), current.decision());
    }

    private State state(long id, boolean lock) {
        return jdbc.sql("""
                SELECT stable_key, version_no, status, created_by, review_version, base_published_version,
                       reviewed_by, reviewed_at, review_note, decision_key, decision_hash, decision
                FROM runbook_document WHERE id=:id
                """ + (lock ? " FOR UPDATE" : "")).param("id", id).query((rs, n) -> new State(
                rs.getString("stable_key"), rs.getInt("version_no"), rs.getString("status"),
                rs.getObject("created_by", Long.class), rs.getInt("review_version"), rs.getInt("base_published_version"),
                rs.getObject("reviewed_by", Long.class), rs.getObject("reviewed_at", LocalDateTime.class),
                rs.getString("review_note"), rs.getString("decision_key"), rs.getString("decision_hash"), rs.getString("decision")))
                .optional().orElseThrow(() -> missing());
    }

    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "RUNBOOK_NOT_FOUND", "候选手册不存在"); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "RUNBOOK_PUBLICATION_INVALID", message); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }

    private record State(String stableKey, int versionNo, String status, Long createdBy, int reviewVersion,
                         int baseVersion, Long reviewedBy, LocalDateTime reviewedAt, String note,
                         String key, String hash, String decision) { }
    public record QueueItem(long id, String stableKey, int versionNo, String title, String status, Long createdBy,
                            LocalDateTime createdAt, int reviewVersion, int basePublishedVersion) { }
    public record Queue(List<QueueItem> items, long total, boolean truncated) { }
    public record Review(RunbookService.DocumentView document, int reviewVersion, int basePublishedVersion,
                         int currentPublishedVersion, Long reviewedBy, LocalDateTime reviewedAt,
                         String reviewNote, String decision) { }
}
