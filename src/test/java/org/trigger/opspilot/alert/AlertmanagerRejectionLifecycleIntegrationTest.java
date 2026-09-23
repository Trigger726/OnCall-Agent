package org.trigger.opspilot.alert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.trigger.opspilot.common.ApiException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-rejection-lifecycle-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "opspilot.alertmanager.webhook.secret=webhook-test-secret",
        "opspilot.alertmanager.rejections.auto-replay-enabled=true",
        "opspilot.alertmanager.rejections.auto-replay-initial-delay=3600000",
        "opspilot.alertmanager.rejections.retry-base-delay=2s",
        "opspilot.alertmanager.rejections.retry-max-delay=8s",
        "opspilot.alertmanager.rejections.max-auto-replay-attempts=2",
        "opspilot.agent.recovery.enabled=false"
})
class AlertmanagerRejectionLifecycleIntegrationTest {
    @Autowired private AlertmanagerWebhookService webhookService;
    @Autowired private AlertmanagerRejectionService rejectionService;
    @Autowired private AlertmanagerRejectionLifecycleJob job;
    @Autowired private JdbcClient jdbcClient;

    @Test
    void shouldBoundAutomaticReplayAndNotResetAttemptsOnDuplicateDelivery() {
        String fingerprint = "am-auto-31-success";
        long id = reject(fingerprint, "APP-AUTO-31-SUCCESS");
        forceDue(id);
        job.replayDue();

        assertThat(integer(id, "auto_replay_count")).isEqualTo(1);
        assertThat(integer(id, "replay_count")).isEqualTo(1);
        assertThat(string(id, "status")).isEqualTo("OPEN");
        LocalDateTime retryAt = dateTime(id, "next_auto_replay_at");
        assertThat(retryAt).isAfter(LocalDateTime.now());
        assertThat(retryAt).isBefore(LocalDateTime.now().plusSeconds(3));
        assertThat(reject(fingerprint, "APP-AUTO-31-SUCCESS")).isEqualTo(id);
        assertThat(integer(id, "auto_replay_count")).isEqualTo(1);
        assertThat(dateTime(id, "next_auto_replay_at")).isEqualTo(retryAt);

        addResource("APP-AUTO-31-SUCCESS");
        forceDue(id);
        job.replayDue();
        job.replayDue();

        assertThat(string(id, "status")).isEqualTo("SUCCEEDED");
        assertThat(integer(id, "auto_replay_count")).isEqualTo(2);
        assertThat(integer(id, "replay_count")).isEqualTo(2);
        assertThat(dateTime(id, "next_auto_replay_at")).isNull();
        assertThat(alertCount(fingerprint)).isEqualTo(1);
        assertThat(auditCount(id, "ALERTMANAGER_REJECTION_AUTO_REPLAYED")).isEqualTo(1);
        assertThat(auditCount(id, "ALERTMANAGER_REJECTION_AUTO_REPLAY_FAILED")).isEqualTo(1);
    }

    @Test
    void shouldExhaustAutomaticAttemptsButAllowManualReplayAfterFix() {
        String fingerprint = "am-auto-31-exhausted";
        long id = reject(fingerprint, "APP-AUTO-31-EXHAUSTED");
        forceDue(id);
        job.replayDue();
        forceDue(id);
        job.replayDue();
        job.replayDue();

        assertThat(string(id, "status")).isEqualTo("OPEN");
        assertThat(integer(id, "auto_replay_count")).isEqualTo(2);
        assertThat(integer(id, "replay_count")).isEqualTo(2);
        assertThat(dateTime(id, "next_auto_replay_at")).isNull();
        assertThat(dateTime(id, "auto_replay_exhausted_at")).isNotNull();
        assertThat(reject(fingerprint, "APP-AUTO-31-EXHAUSTED")).isEqualTo(id);
        assertThat(integer(id, "auto_replay_count")).isEqualTo(2);
        assertThat(dateTime(id, "next_auto_replay_at")).isNull();

        addResource("APP-AUTO-31-EXHAUSTED");
        assertThat(webhookService.replay(id, null).action()).isEqualTo("CREATED");
        assertThat(string(id, "status")).isEqualTo("SUCCEEDED");
        assertThat(alertCount(fingerprint)).isEqualTo(1);
    }

    @Test
    void shouldClaimDueRejectionOnlyOnceAcrossConcurrentWorkers() throws Exception {
        long id = reject("am-auto-31-concurrent", "APP-AUTO-31-CONCURRENT");
        LocalDateTime claimAt = LocalDateTime.now().plusMinutes(1);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return rejectionService.claimAutomatic(id, claimAt);
            });
            var second = executor.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return rejectionService.claimAutomatic(id, claimAt);
            });
            start.countDown();
            var claims = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(claims.stream().filter(java.util.Optional::isPresent).count()).isEqualTo(1);
            assertThat(integer(id, "auto_replay_count")).isEqualTo(1);
            rejectionService.release(id, claims.stream().filter(java.util.Optional::isPresent)
                    .findFirst().orElseThrow().orElseThrow().token(), "TEST_RELEASE", "lease released");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldApplyJitterWithinExponentialAndMaximumBounds() {
        assertDelayWithin(rejectionService.retryDelay(1).toMillis(), 1_000, 2_000);
        assertDelayWithin(rejectionService.retryDelay(2).toMillis(), 2_000, 4_000);
        assertDelayWithin(rejectionService.retryDelay(3).toMillis(), 4_000, 8_000);
        assertDelayWithin(rejectionService.retryDelay(10).toMillis(), 4_000, 8_000);
    }

    @Test
    void shouldKeepOriginalErrorAlignedWithImmutableReplayPayload() {
        String fingerprint = "am-auto-31-error-change";
        long id = reject(fingerprint, "APP-AUTO-31-ERROR-CHANGE");
        assertThat(reject(fingerprint, "APP-AUTO-31-ERROR-CHANGE", "page")).isEqualTo(id);
        assertThat(string(id, "error_code")).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(string(id, "payload_json")).contains("warning").doesNotContain("page");
        assertThat(dateTime(id, "next_auto_replay_at")).isNotNull();
    }

    private static void assertDelayWithin(long millis, long minimum, long maximum) {
        assertThat(millis).isBetween(minimum, maximum);
    }

    @Test
    void shouldPurgeOnlyExpiredUnleasedPayloadAndReviveOnRedelivery() {
        long oldId = reject("am-auto-31-purge", "APP-AUTO-31-PURGE");
        long leasedId = reject("am-auto-31-lease", "APP-AUTO-31-LEASE");
        AlertmanagerRejectionService.ReplayClaim lease = rejectionService.claim(leasedId);
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("UPDATE alert_ingest_rejection SET last_received_at = :old WHERE id IN (:oldId, :leasedId)")
                .param("old", now.minusDays(4)).param("oldId", oldId).param("leasedId", leasedId).update();

        assertThat(rejectionService.purgeExpired(now)).isEqualTo(1);
        assertThat(string(oldId, "payload_status")).isEqualTo("PURGED");
        assertThat(string(oldId, "payload_json")).isEqualTo("[PURGED]");
        assertThat(string(oldId, "resource_code")).isNull();
        assertThat(string(leasedId, "payload_status")).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> rejectionService.claim(oldId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ALERTMANAGER_REJECTION_PAYLOAD_PURGED"));
        assertThat(auditCount(null, "ALERTMANAGER_REJECTION_PAYLOAD_PURGED")).isPositive();

        assertThat(reject("am-auto-31-purge", "APP-AUTO-31-PURGE")).isEqualTo(oldId);
        assertThat(string(oldId, "payload_status")).isEqualTo("ACTIVE");
        assertThat(string(oldId, "payload_json")).contains("APP-AUTO-31-PURGE");
        assertThat(integer(oldId, "delivery_count")).isEqualTo(2);
        rejectionService.release(leasedId, lease.token(), "TEST_RELEASE", "test lease released");
    }

    private long reject(String fingerprint, String resourceCode) {
        return reject(fingerprint, resourceCode, "warning");
    }

    private long reject(String fingerprint, String resourceCode, String severity) {
        var alert = new AlertmanagerWebhookService.Alert("firing",
                Map.of("alertname", "LifecycleAlert", "resource_code", resourceCode, "severity", severity),
                Map.of("summary", "Lifecycle test"), OffsetDateTime.parse("2026-09-23T01:00:00Z"),
                OffsetDateTime.parse("2026-09-23T02:00:00Z"), null, fingerprint);
        var webhook = new AlertmanagerWebhookService.Webhook("4", "lifecycle-group", "firing", "opspilot",
                Map.of(), Map.of(), Map.of(), null, List.of(alert));
        var result = webhookService.receive("OpsPilot webhook-test-secret", webhook);
        assertThat(result.rejected()).isEqualTo(1);
        return result.items().get(0).rejectionId();
    }

    private void forceDue(long id) {
        jdbcClient.sql("UPDATE alert_ingest_rejection SET next_auto_replay_at = :due WHERE id = :id")
                .param("due", LocalDateTime.now().minusSeconds(1)).param("id", id).update();
    }

    private void addResource(String resourceCode) {
        jdbcClient.sql("""
                        INSERT INTO cmdb_resource(resource_code, resource_type, name, environment, status,
                          owner_user_id, description, attributes_json)
                        VALUES (:code, 'APPLICATION', '自动重放测试资源', 'PRODUCTION', 'RUNNING',
                          2, '生命周期测试', '{}')
                        """).param("code", resourceCode).update();
    }

    private int alertCount(String fingerprint) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM alert_event WHERE source = 'alertmanager'
                          AND external_event_id = :externalId
                        """).param("externalId", fingerprint + ":1790125200000")
                .query(Integer.class).single();
    }

    private int auditCount(Long id, String action) {
        String where = id == null ? "" : " AND target_id = :id";
        var query = jdbcClient.sql("SELECT COUNT(*) FROM audit_log WHERE action = :action" + where)
                .param("action", action);
        if (id != null) query = query.param("id", Long.toString(id));
        return query.query(Integer.class).single();
    }

    private int integer(long id, String column) {
        return jdbcClient.sql("SELECT " + column + " FROM alert_ingest_rejection WHERE id = :id")
                .param("id", id).query(Integer.class).single();
    }

    private String string(long id, String column) {
        return jdbcClient.sql("SELECT " + column + " FROM alert_ingest_rejection WHERE id = :id")
                .param("id", id).query(String.class).optional().orElse(null);
    }

    private LocalDateTime dateTime(long id, String column) {
        return jdbcClient.sql("SELECT " + column + " FROM alert_ingest_rejection WHERE id = :id")
                .param("id", id).query(LocalDateTime.class).optional().orElse(null);
    }
}
