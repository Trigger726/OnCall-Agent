package org.trigger.opspilot.investigation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.trigger.opspilot.incident.IncidentService;
import org.trigger.opspilot.investigation.tool.InvestigationTool;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-run-recovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false",
        "opspilot.agent.recovery.enabled=false"
})
@Import(AgentRunRecoveryIntegrationTest.ControlledToolConfig.class)
class AgentRunRecoveryIntegrationTest {
    @Autowired private InvestigationService investigationService;
    @Autowired private AgentRunRecoveryService recoveryService;
    @Autowired private AgentRunEventService eventService;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private ControlledRecoveryTool controlledTool;

    @Test
    void shouldSettleExpiredRunningTaskOnceAndFenceLateToolResult() throws Exception {
        InvestigationService.RunActor actor = new InvestigationService.RunActor(1L, "127.0.0.1");
        InvestigationService.PreparedRun prepared = investigationService.prepare(
                1, "RECOVERY_RACE_TEST", actor, "recovery-" + UUID.randomUUID(), Duration.ofSeconds(30));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<InvestigationService.InvestigationResult> future = executor.submit(() ->
                    investigationService.execute(prepared, actor, AgentRunEventService.EventSink.NOOP));
            assertThat(controlledTool.entered.await(5, TimeUnit.SECONDS)).isTrue();

            LocalDateTime recoveryAt = LocalDateTime.now();
            jdbcClient.sql("UPDATE agent_investigation_run SET deadline_at = :deadline WHERE id = :runId")
                    .param("deadline", recoveryAt.minusSeconds(1)).param("runId", prepared.runId()).update();
            AgentRunRecoveryService.RecoveryResult recovered = recoveryService.reconcileExpired(recoveryAt);
            assertThat(recovered.settledRunIds()).containsExactly(prepared.runId());
            assertThat(recoveryService.reconcileExpired(recoveryAt.plusSeconds(1)).settled()).isZero();

            controlledTool.release.countDown();
            InvestigationService.InvestigationResult result = future.get(5, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo("TIMED_OUT");
            assertThat(result.reportId()).isNull();

            AgentRunQueryService.AgentRunView run = investigationService.listRuns(1).stream()
                    .filter(item -> item.id().equals(prepared.runId())).findFirst().orElseThrow();
            assertThat(run.status()).isEqualTo("TIMED_OUT");
            assertThat(run.terminationKind()).isEqualTo("TIMEOUT");
            assertThat(run.steps()).noneMatch(step -> "controlled_recovery_boundary".equals(step.toolName()));
            List<AgentRunEventService.EventView> events = eventService.list(prepared.runId(), 0);
            assertThat(events).filteredOn(event -> "RUN_TIMED_OUT".equals(event.eventType())).hasSize(1);
            assertThat(events.get(events.size() - 1).eventType()).isEqualTo("RUN_TIMED_OUT");
            assertThat(events.get(events.size() - 1).payloadJson())
                    .contains("\"recovered\":true", "DEADLINE_RECONCILIATION");
            assertThat(jdbcClient.sql("""
                            SELECT COUNT(*) FROM incident_timeline
                            WHERE event_type = 'AGENT_RUN_RECOVERY' AND evidence_ref = :evidenceRef
                            """).param("evidenceRef", "agent-run:" + prepared.runId())
                    .query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbcClient.sql("""
                            SELECT COUNT(*) FROM audit_log
                            WHERE action = 'AGENT_RUN_RECOVERY' AND target_id = :runId
                            """).param("runId", Long.toString(prepared.runId()))
                    .query(Integer.class).single()).isEqualTo(1);
        } finally {
            controlledTool.release.countDown();
            executor.shutdownNow();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledToolConfig {
        @Bean
        ControlledRecoveryTool controlledRecoveryTool() {
            return new ControlledRecoveryTool();
        }
    }

    static final class ControlledRecoveryTool implements InvestigationTool {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public int order() {
            return 26;
        }

        @Override
        public String name() {
            return "controlled_recovery_boundary";
        }

        @Override
        public String title() {
            return "等待恢复协调器结算";
        }

        @Override
        public ToolResult execute(IncidentService.IncidentDetail incident) {
            entered.countDown();
            try {
                if (!release.await(8, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for recovery test release");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Recovery test tool interrupted", exception);
            }
            return new ToolResult("恢复结算后工具晚返回", List.of());
        }
    }
}
