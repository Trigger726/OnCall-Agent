package org.trigger.opspilot.investigation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-event-transaction;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false",
        "opspilot.agent.events.outbox-enabled=true",
        "opspilot.agent.events.relay-initial-delay=3600000"
})
class AgentEventTransactionIntegrationTest {
    @Autowired private InvestigationService investigations;
    @Autowired private AgentRunEventService events;
    @Autowired private TransactionTemplate transactions;
    @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @Autowired private AgentEventOutbox outbox;

    @Test
    void shouldRejectOutboxOperationsInsideCallerTransaction() {
        long runId = prepareRun();
        var now = java.time.LocalDateTime.now().plusDays(1);
        long eventId = events.list(runId, 0).get(0).id();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transactions.execute(status ->
                outbox.claim(100, now, Duration.ofSeconds(30))))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var claim = outbox.claim(100, now, Duration.ofSeconds(30)).stream()
                .filter(c -> c.eventId() == eventId).findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transactions.execute(status ->
                outbox.delivered(claim, now)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transactions.execute(status ->
                outbox.retry(claim, now, Duration.ofSeconds(5))))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(outbox.delivered(claim, now)).isTrue();
    }

    @Test
    void shouldReclaimExpiredLeaseAndFenceOldOwner() {
        long runId = prepareRun();
        var now = java.time.LocalDateTime.now().plusDays(1);
        var first = outbox.claim(100, now, Duration.ofSeconds(10)).stream()
                .filter(c -> events.list(runId, 0).stream().anyMatch(e -> e.id() == c.eventId()))
                .findFirst().orElseThrow();
        var later = now.plusSeconds(10);
        var replacement = outbox.claim(100, later, Duration.ofSeconds(10)).stream()
                .filter(c -> c.eventId() == first.eventId()).findFirst().orElseThrow();
        assertThat(outbox.delivered(first, later)).isFalse();
        assertThat(outbox.retry(first, later, Duration.ofSeconds(5))).isFalse();
        assertThat(outbox.retry(replacement, later, Duration.ofSeconds(5))).isTrue();
        assertThat(outbox.claim(100, later.plusSeconds(4), Duration.ofSeconds(10)))
                .extracting(AgentEventOutbox.Claim::eventId).doesNotContain(first.eventId());
        var retried = outbox.claim(100, later.plusSeconds(5), Duration.ofSeconds(10)).stream()
                .filter(c -> c.eventId() == first.eventId()).findFirst().orElseThrow();
        assertThat(outbox.delivered(retried, later.plusSeconds(6))).isTrue();
        assertThat(outbox.delivered(retried, later.plusSeconds(6))).isFalse();
    }

    @Test
    void shouldPublishOnlyAfterOuterCommitAndReplayTheSameEvent() {
        long runId = prepareRun();
        var delivered = new ArrayList<AgentRunEventService.EventView>();
        var recorded = transactions.execute(status -> {
            var event = record(runId, delivered::add);
            assertThat(delivered).isEmpty();
            return event;
        });
        assertThat(recorded).isNotNull();
        assertThat(outboxCount(recorded.id())).isEqualTo(1);
        assertThat(delivered).containsExactly(recorded);
        assertThat(events.list(runId, recorded.id() - 1))
                .extracting(AgentRunEventService.EventView::id).containsExactly(recorded.id());
    }

    @Test
    void shouldNotPublishOrConsumeSequenceWhenOuterTransactionRollsBack() {
        long runId = prepareRun();
        var before = events.list(runId, 0);
        var delivered = new ArrayList<AgentRunEventService.EventView>();
        var rolledBack = transactions.execute(status -> {
            var event = record(runId, delivered::add);
            status.setRollbackOnly();
            return event;
        });
        assertThat(delivered).isEmpty();
        assertThat(events.list(runId, 0)).isEqualTo(before);
        assertThat(outboxCount(rolledBack.id())).isZero();
        var committed = record(runId, delivered::add);
        assertThat(rolledBack).isNotNull();
        assertThat(committed.sequence()).isEqualTo(rolledBack.sequence());
        assertThat(delivered).containsExactly(committed);
    }

    private long prepareRun() {
        return investigations.prepare(1, "EVENT_TX_TEST",
                new InvestigationService.RunActor(1L, "127.0.0.1"),
                UUID.randomUUID().toString(), Duration.ofSeconds(30)).runId();
    }

    private long outboxCount(long eventId) {
        return jdbc.sql("SELECT COUNT(*) FROM agent_event_outbox WHERE event_id = :id")
                .param("id", eventId).query(Long.class).single();
    }

    @Test
    void shouldKeepCommittedEventsAndNotifyLaterSubscribersWhenOneSinkFails() {
        long runId = prepareRun();
        var delivered = new ArrayList<AgentRunEventService.EventView>();
        var committed = transactions.execute(status -> {
            var first = record(runId, event -> { throw new IllegalStateException("sink unavailable"); });
            var second = record(runId, delivered::add);
            return java.util.List.of(first, second);
        });
        assertThat(committed).hasSize(2);
        assertThat(delivered).containsExactly(committed.get(1));
        assertThat(events.list(runId, committed.get(0).id() - 1))
                .extracting(AgentRunEventService.EventView::id)
                .containsExactly(committed.get(0).id(), committed.get(1).id());
    }

    private AgentRunEventService.EventView record(long runId, AgentRunEventService.EventSink sink) {
        return events.record(runId, "STEP_STARTED", "EXECUTE", "test", "RUNNING",
                Map.of("message", "transaction boundary"), sink);
    }

    @Test
    void shouldFinishInvestigationWhenEveryLiveDeliveryFails() {
        var actor = new InvestigationService.RunActor(1L, "127.0.0.1");
        var prepared = investigations.prepare(1, "SINK_FAILURE_TEST", actor,
                UUID.randomUUID().toString(), Duration.ofSeconds(30));
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var result = investigations.execute(prepared, actor, event -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("sink unavailable");
        });
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.reportId()).isNotNull();
        var replay = events.list(prepared.runId(), 0);
        assertThat(attempts.get()).isEqualTo(replay.size() - 1); // RUN_QUEUED used NOOP.
        assertThat(replay).extracting(AgentRunEventService.EventView::eventType)
                .doesNotContain("RUN_FAILED", "STEP_FAILED");
        assertThat(replay.get(replay.size() - 1).eventType()).isEqualTo("RUN_COMPLETED");
    }
}
