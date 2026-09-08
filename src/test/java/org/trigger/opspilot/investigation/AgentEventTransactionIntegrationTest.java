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
        "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false"
})
class AgentEventTransactionIntegrationTest {
    @Autowired private InvestigationService investigations;
    @Autowired private AgentRunEventService events;
    @Autowired private TransactionTemplate transactions;

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
