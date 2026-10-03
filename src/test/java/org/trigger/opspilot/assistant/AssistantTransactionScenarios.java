package org.trigger.opspilot.assistant;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.security.SessionAuthorization;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared committed H2/MySQL fixtures. These use deterministic evidence answers, not a mock security filter. */
public final class AssistantTransactionScenarios implements AutoCloseable {
    private final JdbcClient jdbc;
    private final AssistantService assistant;
    private final SessionAuthorization authorization;
    private final PlatformTransactionManager manager;
    private final DataSource dataSource;
    private final String username = "cp69_" + UUID.randomUUID().toString().replace("-", "");
    private final long ownerId;
    private final long sessionId;

    public AssistantTransactionScenarios(JdbcClient jdbc, AssistantService assistant, SessionAuthorization authorization,
            PlatformTransactionManager manager, DataSource dataSource) {
        this.jdbc = jdbc; this.assistant = assistant; this.authorization = authorization;
        this.manager = manager; this.dataSource = dataSource;
        jdbc.sql("""
                INSERT INTO sys_user(username,password_hash,display_name,role_code,status)
                SELECT :name,password_hash,'Assistant transaction fixture','AUDITOR','ACTIVE'
                FROM sys_user WHERE username='admin'
                """).param("name", username).update();
        ownerId = jdbc.sql("SELECT id FROM sys_user WHERE username=:name").param("name", username).query(Long.class).single();
        sessionId = assistant.createSession(ownerId, null, null).session().id();
        assertThat(title()).isEqualTo("新对话");
    }

    public void auditInsertFailureRollsBackAnswerAndTitle() throws Exception {
        String constraint = "cp69_audit_" + UUID.randomUUID().toString().replace("-", "");
        final boolean mysql;
        try (var connection = dataSource.getConnection()) {
            mysql = "MySQL".equals(connection.getMetaData().getDatabaseProductName());
        }
        jdbc.sql("ALTER TABLE audit_log ADD CONSTRAINT " + constraint
                + " CHECK(action <> 'ASSISTANT_MESSAGE' OR target_type <> 'ASSISTANT_SESSION' OR target_id <> '" + sessionId + "')").update();
        try {
            assertThatThrownBy(() -> assistant.sendMessage(sessionId, ownerId, "atomic audit failure fixture"))
                    .isInstanceOfSatisfying(DataAccessException.class, error -> {
                        assertThat(error.getMostSpecificCause()).isInstanceOf(java.sql.SQLException.class);
                        var sql = (java.sql.SQLException) error.getMostSpecificCause();
                        assertThat(sql.getErrorCode()).isEqualTo(mysql ? 3819 : 23513);
                        assertThat(sql.getSQLState()).isEqualTo(mysql ? "HY000" : "23513");
                        assertThat(sql.getMessage()).containsIgnoringCase(constraint);
                    });
            assertUncompleted();
        } finally {
            jdbc.sql("ALTER TABLE audit_log DROP " + (mysql ? "CHECK " : "CONSTRAINT ") + constraint).update();
        }
        var fresh = assistant.sendMessage(sessionId, ownerId, "valid after constraint removal");
        assertThat(fresh.role()).isEqualTo("ASSISTANT");
        assertThat(messages("ASSISTANT")).isEqualTo(1);
        assertThat(completions()).isEqualTo(1);
    }

    public void finalStopRollsBackAnswerTitleAndAlreadyInsertedAudit() {
        var observed = new AtomicBoolean();
        var lease = authorization.capture(ownerId);
        assertThatThrownBy(() -> assistant.sendMessage(sessionId, ownerId, "atomic completion stop fixture", lease, () -> {
            if (TransactionSynchronizationManager.isActualTransactionActive() && completions() == 1) {
                assertThat(messages("ASSISTANT")).isEqualTo(1);
                assertThat(title()).isEqualTo("atomic completion stop fixture");
                observed.set(true);
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "CONTROLLED_ASSISTANT_STOP", "controlled completion stop");
            }
        })).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("CONTROLLED_ASSISTANT_STOP"));
        assertThat(observed).isTrue();
        assertUncompleted();
    }

    public void finalAccountLockWaitSeesCommittedRevocation() throws Exception { finalAccountLockWait(true); }
    public void finalAccountLockWaitRechecksExpiry() throws Exception { finalAccountLockWait(false); }

    private AssistantRequestStore.Identity identity(String key) {
        return new AssistantRequestStore.Identity(AssistantRequestStore.keyHash(key), UUID.randomUUID().toString(), System.currentTimeMillis() + 30000);
    }

    public void completedKeyBindsQuestionAndPreservesCaseSensitiveSessionScope() {
        String key = "ScopedKey-" + UUID.randomUUID();
        var lease = authorization.capture(ownerId);
        var first = assistant.sendMessage(sessionId, ownerId, "bound question", lease, () -> {}, identity(key));
        var repeat = assistant.sendMessage(sessionId, ownerId, "bound question", lease, () -> {}, identity(key));
        assertThat(repeat.id()).isEqualTo(first.id());
        assertThatThrownBy(() -> assistant.sendMessage(sessionId, ownerId, "changed question", lease, () -> {}, identity(key)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("ASSISTANT_IDEMPOTENCY_CONFLICT"));
        assertThat(messages("USER")).isEqualTo(1); assertThat(messages("ASSISTANT")).isEqualTo(1); assertThat(completions()).isEqualTo(1);
        assertThat(assistant.sendMessage(sessionId, ownerId, "bound question", lease, () -> {}, identity(key.toLowerCase(java.util.Locale.ROOT))).id()).isNotEqualTo(first.id());
        assertThat(messages("USER")).isEqualTo(2); assertThat(messages("ASSISTANT")).isEqualTo(2); assertThat(completions()).isEqualTo(2);
        long otherId = assistant.createSession(ownerId, null, null).session().id();
        try {
            assertThat(assistant.sendMessage(otherId, ownerId, "independent question", lease, () -> {}, identity(key)).role()).isEqualTo("ASSISTANT");
            assertThat(jdbc.sql("SELECT COUNT(*) FROM assistant_request WHERE session_id=:id").param("id", otherId).query(Long.class).single()).isEqualTo(1);
        } finally { jdbc.sql("DELETE FROM assistant_session WHERE id=:id AND owner_user_id=:owner").param("id", otherId).param("owner", ownerId).update(); }
    }

    public void keyedCompletionRollsBackStateAnswerTitleAndAuditTogether() {
        var identity = identity(UUID.randomUUID().toString());
        var observed = new AtomicBoolean();
        var lease = authorization.capture(ownerId);
        assertThatThrownBy(() -> assistant.sendMessage(sessionId, ownerId, "atomic keyed completion", lease, () -> {
            if (TransactionSynchronizationManager.isActualTransactionActive() && completions() == 1) {
                assertThat(jdbc.sql("SELECT status FROM assistant_request WHERE session_id=:id").param("id", sessionId).query(String.class).single()).isEqualTo("COMPLETED");
                assertThat(messages("ASSISTANT")).isEqualTo(1); observed.set(true);
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "CONTROLLED_ASSISTANT_STOP", "controlled keyed completion stop");
            }
        }, identity)).isInstanceOf(ApiException.class);
        assertThat(observed).isTrue(); assertUncompleted();
        assertThat(jdbc.sql("SELECT status FROM assistant_request WHERE session_id=:id").param("id", sessionId).query(String.class).single()).isEqualTo("TIMED_OUT");
        assertThat(jdbc.sql("SELECT answer_message_id FROM assistant_request WHERE session_id=:id").param("id", sessionId).query(Long.class).single()).isNull();
        assertThatThrownBy(() -> assistant.sendMessage(sessionId, ownerId, "atomic keyed completion", lease, () -> {}, identity))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("ASSISTANT_REQUEST_TIMED_OUT"));
        assertUncompleted();
    }

    public void concurrentKeyNeverCreatesSecondQuestionOrStopsOriginalAttempt() throws Exception {
        String key = UUID.randomUUID().toString();
        var original = identity(key); var duplicate = identity(key);
        var lease = authorization.capture(ownerId);
        var prepared = new CountDownLatch(1); var release = new CountDownLatch(1);
        var observed = new AtomicBoolean();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> assistant.sendMessage(sessionId, ownerId, "concurrent key question", lease, () -> {
                if (!TransactionSynchronizationManager.isActualTransactionActive() && observed.compareAndSet(false, true)) {
                    prepared.countDown(); await(release);
                }
            }, original));
            assertThat(prepared.await(3, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> assistant.sendMessage(sessionId, ownerId, "concurrent key question", lease, () -> {}, duplicate));
            assertThatThrownBy(() -> second.get(3, TimeUnit.SECONDS)).isInstanceOfSatisfying(java.util.concurrent.ExecutionException.class,
                    error -> assertThat(error.getCause()).isInstanceOfSatisfying(ApiException.class,
                            api -> assertThat(api.code()).isEqualTo("ASSISTANT_REQUEST_IN_PROGRESS")));
            assistant.stopRequest(sessionId, duplicate.keyHash(), duplicate.attemptId(), AssistantExecutionManager.Reason.TIMED_OUT);
            assertThat(jdbc.sql("SELECT status FROM assistant_request WHERE session_id=:id").param("id", sessionId).query(String.class).single()).isEqualTo("RUNNING");
            assertThat(messages("USER")).isEqualTo(1); assertThat(messages("ASSISTANT")).isZero(); assertThat(completions()).isZero();
            release.countDown(); assertThat(first.get(3, TimeUnit.SECONDS).role()).isEqualTo("ASSISTANT");
            assertThat(messages("USER")).isEqualTo(1); assertThat(messages("ASSISTANT")).isEqualTo(1); assertThat(completions()).isEqualTo(1);
        } finally { release.countDown(); pool.shutdown(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    public void expiryDuringCompletionRollsBackAnswerTitleAndAudit() {
        var expiry = Instant.now().plusSeconds(2);
        var lease = new SessionAuthorization.Lease(ownerId, username, 0, expiry);
        var observed = new AtomicBoolean();
        assertThatThrownBy(() -> assistant.sendMessage(sessionId, ownerId, "expiry during completion fixture", lease, () -> {
            if (TransactionSynchronizationManager.isActualTransactionActive() && completions() == 1) {
                assertThat(messages("ASSISTANT")).isEqualTo(1);
                assertThat(Instant.now()).isBefore(expiry);
                observed.set(true);
                while (Instant.now().isBefore(expiry)) {
                    try { Thread.sleep(10); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                }
            }
        })).isInstanceOf(CredentialsExpiredException.class);
        assertThat(observed).isTrue();
        assertUncompleted();
    }

    private void finalAccountLockWait(boolean revoke) throws Exception {
        var expiry = Instant.now().plusSeconds(revoke ? 30 : 3);
        var lease = new SessionAuthorization.Lease(ownerId, username, 0, expiry);
        var outsidePreparation = new AtomicBoolean();
        var finalArrived = new CountDownLatch(1);
        var enterFinal = new CountDownLatch(1);
        var rowLocked = new CountDownLatch(1);
        var commitHolder = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var holderTransaction = new TransactionTemplate(manager);
        holderTransaction.setTimeout(10);
        try {
            var answer = pool.submit(() -> assistant.sendMessage(sessionId, ownerId, "final lock wait fixture", lease, () -> {
                if (!TransactionSynchronizationManager.isActualTransactionActive()) outsidePreparation.set(true);
                else if (outsidePreparation.get() && finalArrived.getCount() == 1) {
                    // Establish a snapshot before FOR UPDATE as well as proving the accepted USER is committed.
                    assertThat(messages("USER")).isEqualTo(1);
                    finalArrived.countDown(); await(enterFinal);
                }
            }));
            assertThat(finalArrived.await(3, TimeUnit.SECONDS)).isTrue();
            var holder = pool.submit(() -> holderTransaction.execute(status -> {
                jdbc.sql("SELECT id FROM sys_user WHERE id=:id FOR UPDATE").param("id", ownerId).query(Long.class).single();
                if (revoke) jdbc.sql("UPDATE sys_user SET auth_version=auth_version+1 WHERE id=:id").param("id", ownerId).update();
                rowLocked.countDown(); await(commitHolder); return true;
            }));
            assertThat(rowLocked.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(Instant.now()).isBefore(expiry);
            enterFinal.countDown();
            assertThatThrownBy(() -> answer.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            if (!revoke) while (Instant.now().isBefore(expiry)) Thread.sleep(10);
            commitHolder.countDown();
            assertThat(holder.get(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> answer.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(CredentialsExpiredException.class);
            assertUncompleted();
            assertThat(jdbc.sql("SELECT auth_version FROM sys_user WHERE id=:id").param("id", ownerId).query(Long.class).single())
                    .isEqualTo(revoke ? 1 : 0);
            assertThat(assistant.sendMessage(sessionId, ownerId, "fresh current lease").role()).isEqualTo("ASSISTANT");
            assertThat(messages("ASSISTANT")).isEqualTo(1);
            assertThat(completions()).isEqualTo(1);
        } finally {
            enterFinal.countDown(); commitHolder.countDown(); pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }

    private void assertUncompleted() {
        assertThat(messages("USER")).isEqualTo(1); // The accepted question is durable, not erased on failure.
        assertThat(messages("ASSISTANT")).isZero();
        assertThat(completions()).isZero();
        assertThat(title()).isEqualTo("新对话");
    }
    private long messages(String role) {
        return jdbc.sql("SELECT COUNT(*) FROM assistant_message WHERE session_id=:id AND role=:role")
                .param("id", sessionId).param("role", role).query(Long.class).single();
    }
    private long completions() {
        return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action='ASSISTANT_MESSAGE' AND target_type='ASSISTANT_SESSION' AND target_id=:id")
                .param("id", String.valueOf(sessionId)).query(Long.class).single();
    }
    private String title() {
        return jdbc.sql("SELECT title FROM assistant_session WHERE id=:id").param("id", sessionId).query(String.class).single();
    }
    @Override public void close() {
        jdbc.sql("DELETE FROM assistant_session WHERE id=:id AND owner_user_id=:owner").param("id", sessionId).param("owner", ownerId).update();
        jdbc.sql("DELETE FROM audit_log WHERE actor_id=:owner").param("owner", ownerId).update();
        jdbc.sql("DELETE FROM sys_user WHERE id=:owner AND username=:name").param("owner", ownerId).param("name", username).update();
    }
}
