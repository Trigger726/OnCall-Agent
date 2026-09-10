package org.trigger.opspilot.investigation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentEventSubscriptionsTest {
    private final AgentRunEventService events = mock(AgentRunEventService.class);
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private final AgentEventSubscriptions hub = new AgentEventSubscriptions(events, tasks::add);

    @Test
    void shouldMergeRegistrationReplayAndConcurrentDuplicateNotifications() {
        var output = new RecordingOutput();
        when(events.page(1, 0, 100)).thenAnswer(call -> {
            hub.notified(new AgentEventReceiver.Notification(1, 999));
            hub.notified(new AgentEventReceiver.Notification(1, 1));
            return List.of(event(1, "RUN_STARTED"));
        });
        when(events.page(1, 1, 100)).thenReturn(List.of(event(2, "RUN_COMPLETED")));
        hub.subscribe(1, 0, output);
        assertThat(hub.size()).isEqualTo(1);
        hub.notified(new AgentEventReceiver.Notification(1, 2));
        assertThat(tasks).hasSize(1);
        tasks.remove().run();
        assertThat(tasks).hasSize(1);
        tasks.remove().run();
        assertThat(output.ids).containsExactly(1L, 2L);
        assertThat(output.completed).isTrue();
        assertThat(hub.size()).isZero();
        hub.notified(new AgentEventReceiver.Notification(1, 2));
        assertThat(tasks).isEmpty();
    }

    @Test
    void shouldRecoverWithoutNotificationsAndReleaseDisconnectedSlots() {
        var output = new RecordingOutput();
        when(events.page(1, 0, 100)).thenReturn(List.of(), List.of(event(1, "RUN_STARTED")));
        hub.subscribe(1, 0, output);
        tasks.remove().run();
        assertThat(output.ids).isEmpty();
        hub.catchUp();
        tasks.remove().run();
        assertThat(output.ids).containsExactly(1L);
        output.disconnect.run();
        output.disconnect.run();
        hub.catchUp();
        assertThat(tasks).isEmpty();
        assertThat(hub.size()).isZero();
    }

    @Test
    void shouldBoundConnectionsAndRejectBeforeSchedulingMoreWork() {
        var outputs = new ArrayList<RecordingOutput>();
        for (int i = 0; i < 128; i++) {
            var output = new RecordingOutput();
            outputs.add(output);
            hub.subscribe(1, 0, output);
        }
        assertThatThrownBy(() -> hub.subscribe(1, 0, new RecordingOutput()))
                .isInstanceOf(org.trigger.opspilot.common.ApiException.class);
        for (int i = 0; i < 100; i++) hub.catchUp();
        assertThat(tasks).hasSize(128);
        outputs.forEach(o -> o.disconnect.run());
        while (!tasks.isEmpty()) tasks.remove().run();
        assertThat(hub.size()).isZero();
        hub.subscribe(1, 0, new RecordingOutput());
        assertThat(hub.size()).isEqualTo(1);
    }

    @Test
    void shouldCloseAtStreamBudgetAndPermitCursorResume() {
        when(events.page(eq(1L), anyLong(), eq(100))).thenAnswer(call -> {
            long cursor = call.getArgument(1);
            return java.util.stream.LongStream.rangeClosed(cursor + 1, cursor + 100)
                    .mapToObj(id -> event(id, "STEP_STARTED")).toList();
        });
        var output = new RecordingOutput();
        hub.subscribe(1, 0, output);
        while (!tasks.isEmpty()) tasks.remove().run();
        assertThat(output.ids).hasSize(1000).doesNotHaveDuplicates();
        assertThat(output.completed).isTrue();
        assertThat(hub.size()).isZero();
        var resumed = new RecordingOutput();
        hub.subscribe(1, 1000, resumed);
        tasks.remove().run();
        assertThat(resumed.ids.get(0)).isEqualTo(1001);
        resumed.disconnect.run();
    }

    @Test
    void shouldCloseAlreadyConsumedTerminalAndIsolateBrokenOutput() {
        when(events.validateCursor(1, 2)).thenReturn(event(2, "RUN_CANCELLED"));
        var terminal = new RecordingOutput();
        hub.subscribe(1, 2, terminal);
        tasks.remove().run();
        assertThat(terminal.completed).isTrue();
        assertThat(terminal.ids).isEmpty();
        when(events.page(1, 0, 100)).thenReturn(List.of(event(1, "RUN_STARTED")));
        var broken = new RecordingOutput() {
            public void send(AgentRunEventService.EventView event) throws IOException {
                throw new IOException("disconnected");
            }
        };
        hub.subscribe(1, 0, broken);
        tasks.remove().run();
        assertThat(hub.size()).isZero();
        assertThat(broken.completed).isFalse();
    }

    @Test
    void shouldKeepNotificationThreadResponsiveWhileOutputIsBlocked() throws Exception {
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var live = new AgentEventSubscriptions(events, worker);
        when(events.page(1, 0, 100)).thenReturn(List.of(event(1, "RUN_STARTED")));
        var output = new RecordingOutput() {
            public void send(AgentRunEventService.EventView event) throws IOException {
                entered.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("test timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
            }
        };
        try {
            live.subscribe(1, 0, output);
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // Would deadlock if notify or close took the sending thread's lock.
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
                for (int i = 0; i < 1000; i++) live.notified(new AgentEventReceiver.Notification(1, i + 1));
                output.disconnect.run();
            });
            assertThat(live.size()).isZero();
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void shouldRetryRejectedWorkerWithoutSendingOnNotificationThread() {
        var reject = new java.util.concurrent.atomic.AtomicBoolean(true);
        Executor executor = task -> {
            if (reject.get()) throw new RejectedExecutionException();
            tasks.add(task);
        };
        var bounded = new AgentEventSubscriptions(events, executor);
        var output = new RecordingOutput();
        when(events.page(1, 0, 100)).thenReturn(List.of(event(1, "RUN_COMPLETED")));
        bounded.subscribe(1, 0, output);
        assertThat(output.ids).isEmpty();
        reject.set(false);
        bounded.catchUp();
        tasks.remove().run();
        assertThat(output.ids).containsExactly(1L);
        assertThat(bounded.size()).isZero();
    }

    private static AgentRunEventService.EventView event(long id, String type) {
        return new AgentRunEventService.EventView(id, 1, (int) id, type, null, null,
                "RUNNING", "{}", LocalDateTime.now());
    }

    private static class RecordingOutput implements AgentEventSubscriptions.Output {
        final List<Long> ids = new ArrayList<>();
        Runnable disconnect;
        boolean completed;
        public void send(AgentRunEventService.EventView event) throws IOException { ids.add(event.id()); }
        public void complete() { completed = true; }
        public void onClose(Runnable callback) { disconnect = callback; }
    }
}
