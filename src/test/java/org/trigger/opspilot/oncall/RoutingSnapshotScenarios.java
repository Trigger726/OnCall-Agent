package org.trigger.opspilot.oncall;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.trigger.opspilot.alert.AlertService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

// The exact same commit barrier and assertions run against H2 and real MySQL.
abstract class RoutingSnapshotScenarios {
    @SpyBean private JdbcClient jdbc;
    @SpyBean private IncidentEscalationService escalation;
    @Autowired private OnCallRosterService roster;
    @Autowired private AlertService alerts;
    @Autowired private DataSource dataSource;

    @Test void shouldObserveCommittedCancellationDuringAlertIntake() throws Exception { exercise(Change.CANCEL, true); }
    @Test void shouldObserveCommittedCancellationDuringScan() throws Exception { exercise(Change.CANCEL, false); }
    @Test void shouldObserveNewOverrideDuringScan() throws Exception { exercise(Change.CREATE_OVERRIDE, false); }
    @Test void shouldObserveDisabledScheduleDuringScan() throws Exception { exercise(Change.DISABLE_SCHEDULE, false); }
    @Test void shouldObserveDisabledUserDuringScan() throws Exception { exercise(Change.DISABLE_USER, false); }
    @Test void shouldObserveDisabledPolicyDuringScan() throws Exception { exercise(Change.DISABLE_POLICY, false); }
    @Test void shouldObserveChangedRoleDuringScan() throws Exception { exercise(Change.CHANGE_ROLE, false); }

    @Test
    void shouldRollBackIntakeAndItsRoutedFactsTogether() {
        LocalDateTime at = jdbc.sql("SELECT CURRENT_TIMESTAMP")
                .query((rs, row) -> rs.getObject(1, LocalDateTime.class)).single().truncatedTo(ChronoUnit.SECONDS);
        var shift = roster.create(new OnCallRosterService.ShiftCommand(1, 2,
                at.minusMinutes(1), at.plusHours(1), false, "回滚验收"), 1L, "test");
        var incident = new AtomicLong();
        String externalId = UUID.randomUUID().toString();
        doAnswer(call -> {
            incident.set(call.getArgument(0));
            call.callRealMethod();
            assertThat(eventCount(incident.get())).isEqualTo(1);
            assertThat(notificationCount(incident.get())).isEqualTo(1);
            throw new IllegalStateException("rollback-sentinel");
        }).when(escalation).routeNewIncident(anyLong());
        assertThatThrownBy(() -> alerts.intake(new AlertService.IntakeRequest("snapshot-rollback", externalId,
                "APP-SETTLEMENT", "P1", "FIRING", "原子回滚 " + externalId, "回滚验收", Map.of(), at)))
                .isInstanceOf(IllegalStateException.class).hasMessage("rollback-sentinel");
        assertThat(incident.get()).isPositive();
        for (String table : new String[]{"incident_escalation_event", "notification_log", "incident_timeline"}) {
            assertThat(jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE incident_id = :id")
                    .param("id", incident.get()).query(Long.class).single()).isZero();
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM incident WHERE id = :id")
                .param("id", incident.get()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM alert_event WHERE source = 'snapshot-rollback' AND external_event_id = :id")
                .param("id", externalId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action LIKE 'INCIDENT_ESCALATION_%' AND target_id = :id")
                .param("id", Long.toString(incident.get())).query(Long.class).single()).isZero();
        roster.cancel(shift.id(), shift.version(), "验收结束", 1L, "test");
    }

    private void exercise(Change change, boolean intake) throws Exception {
        LocalDateTime at = jdbc.sql("SELECT CURRENT_TIMESTAMP")
                .query((rs, row) -> rs.getObject(1, LocalDateTime.class)).single().truncatedTo(ChronoUnit.SECONDS);
        String suffix = UUID.randomUUID().toString();
        String username = "snapshot-" + suffix;
        long user = insert(jdbc.sql("""
                        INSERT INTO sys_user(username, password_hash, display_name, role_code)
                        SELECT :username, password_hash, '快照验收用户', 'ON_CALL' FROM sys_user WHERE id = 2
                        """).param("username", username));
        String resourceCode = "SNAP-" + suffix;
        long resource = insert(jdbc.sql("""
                        INSERT INTO cmdb_resource(resource_code, resource_type, name, environment, status)
                        VALUES (:code, 'APPLICATION', '快照验收服务', 'TEST', 'RUNNING')
                        """).param("code", resourceCode));
        long schedule = insert(jdbc.sql("""
                        INSERT INTO oncall_schedule(service_resource_id, name) VALUES (:resource, '快照班次')
                        """).param("resource", resource));
        long policy = insert(jdbc.sql("""
                        INSERT INTO escalation_policy(service_resource_id, name, severity)
                        VALUES (:resource, '快照策略', 'P1')
                        """).param("resource", resource));
        String type = change == Change.DISABLE_USER ? "USER" : change == Change.CHANGE_ROLE ? "ROLE" : "ON_CALL";
        String role = "SNAP_" + suffix.substring(0, 8);
        String ref = "USER".equals(type) ? Long.toString(user) : "ROLE".equals(type) ? role : "schedule:" + schedule;
        jdbc.sql("""
                        INSERT INTO escalation_step(policy_id, step_order, delay_minutes, target_type, target_ref)
                        VALUES (:policy, 1, 0, :type, :ref)
                        """).param("policy", policy).param("type", type).param("ref", ref).update();
        roster.create(new OnCallRosterService.ShiftCommand(schedule, user,
                at.minusMinutes(1), at.plusHours(1), false, "普通班次"), 1L, "test");
        var cover = change == Change.CANCEL ? roster.create(new OnCallRosterService.ShiftCommand(schedule, 3,
                at.minusMinutes(1), at.plusHours(1), true, "取消前覆盖"), 1L, "test") : null;
        if (change == Change.CHANGE_ROLE) jdbc.sql("UPDATE sys_user SET role_code = :role WHERE id = :id")
                .param("role", role).param("id", user).update();
        long seededIncident = intake ? 0 : insert(jdbc.sql("""
                        INSERT INTO incident(incident_code, title, severity, status, service_resource_id, created_at)
                        VALUES (:code, '扫描快照验收', 'P1', 'OPEN', :resource, :at)
                        """).param("code", "INC-" + suffix).param("resource", resource).param("at", at.minusSeconds(1)));

        var ready = new CountDownLatch(1);
        var changed = new CountDownLatch(1);
        var paused = new AtomicBoolean();
        var isolation = new AtomicInteger();
        Runnable pause = () -> {
            if (!paused.compareAndSet(false, true)) return;
            var connection = DataSourceUtils.getConnection(dataSource);
            try {
                isolation.set(connection.getTransactionIsolation());
                // Establish an ordinary-read snapshot before the concurrent commit.
                assertThat(jdbc.sql("SELECT COUNT(*) FROM oncall_shift WHERE schedule_id = :id")
                        .param("id", schedule).query(Long.class).single()).isPositive();
                ready.countDown();
                await(changed);
            } catch (java.sql.SQLException error) {
                throw new IllegalStateException(error);
            } finally { DataSourceUtils.releaseConnection(connection, dataSource); }
        };
        if (intake) {
            doAnswer(call -> { pause.run(); return call.callRealMethod(); })
                    .when(escalation).routeNewIncident(anyLong());
        } else {
            doAnswer(call -> { pause.run(); return call.callRealMethod(); }).when(jdbc)
                    .sql(argThat(sql -> sql.contains("FROM incident WHERE id = :id FOR UPDATE")));
        }
        var executor = Executors.newSingleThreadExecutor();
        long incident;
        try {
            var reading = executor.submit(() -> {
                if (intake) return alerts.intake(new AlertService.IntakeRequest("snapshot-test", suffix,
                        resourceCode, "P1", "FIRING", "接入快照验收 " + suffix, "独立夹具", Map.of(), at)).incidentId();
                escalation.scan(at, null, "snapshot-test");
                return seededIncident;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            switch (change) {
                case CANCEL -> roster.cancel(cover.id(), cover.version(), "撤销已提交", 1L, "test");
                case CREATE_OVERRIDE -> roster.create(new OnCallRosterService.ShiftCommand(schedule, 3,
                        at.minusMinutes(1), at.plusHours(1), true, "新覆盖已提交"), 1L, "test");
                case DISABLE_SCHEDULE -> jdbc.sql("UPDATE oncall_schedule SET active = FALSE WHERE id = :id")
                        .param("id", schedule).update();
                case DISABLE_USER -> jdbc.sql("UPDATE sys_user SET status = 'DISABLED' WHERE id = :id")
                        .param("id", user).update();
                case DISABLE_POLICY -> jdbc.sql("UPDATE escalation_policy SET active = FALSE WHERE id = :id")
                        .param("id", policy).update();
                case CHANGE_ROLE -> jdbc.sql("UPDATE sys_user SET role_code = 'AUDITOR' WHERE id = :id")
                        .param("id", user).update();
            }
            // Each service call / auto-commit UPDATE above has completed before the read resumes.
            changed.countDown();
            incident = reading.get(20, TimeUnit.SECONDS);
        } finally { changed.countDown(); executor.shutdownNow(); }

        if (change == Change.DISABLE_POLICY) {
            assertThat(eventCount(incident)).isZero();
            assertThat(notificationCount(incident)).isZero();
        } else if (change == Change.DISABLE_SCHEDULE || change == Change.DISABLE_USER || change == Change.CHANGE_ROLE) {
            assertThat(jdbc.sql("SELECT status FROM incident_escalation_event WHERE incident_id = :id")
                    .param("id", incident).query(String.class).single()).isEqualTo("NO_TARGET");
            assertThat(notificationCount(incident)).isZero();
        } else {
            String expected = change == Change.CREATE_OVERRIDE ? "lina" : username;
            assertThat(jdbc.sql("SELECT recipient FROM incident_escalation_event WHERE incident_id = :id")
                    .param("id", incident).query(String.class).single()).isEqualTo(expected);
            assertThat(jdbc.sql("SELECT recipient FROM notification_log WHERE incident_id = :id")
                    .param("id", incident).query(String.class).single()).isEqualTo(expected);
        }
        assertThat(isolation.get()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE target_id = :id AND action LIKE 'INCIDENT_ESCALATION_%'")
                .param("id", Long.toString(incident)).query(Long.class).single())
                .isEqualTo(change == Change.DISABLE_POLICY ? 0 : 1);
        jdbc.sql("UPDATE incident SET status = 'ACKNOWLEDGED' WHERE id = :id").param("id", incident).update();
    }

    private long eventCount(long incident) {
        return jdbc.sql("SELECT COUNT(*) FROM incident_escalation_event WHERE incident_id = :id")
                .param("id", incident).query(Long.class).single();
    }

    private long notificationCount(long incident) {
        return jdbc.sql("SELECT COUNT(*) FROM notification_log WHERE incident_id = :id")
                .param("id", incident).query(Long.class).single();
    }

    private static long insert(JdbcClient.StatementSpec statement) {
        var key = new GeneratedKeyHolder();
        statement.update(key, "id");
        return key.getKey().longValue();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("commit barrier timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("commit barrier interrupted", error);
        }
    }

    private enum Change { CANCEL, CREATE_OVERRIDE, DISABLE_SCHEDULE, DISABLE_USER, DISABLE_POLICY, CHANGE_ROLE }
}
