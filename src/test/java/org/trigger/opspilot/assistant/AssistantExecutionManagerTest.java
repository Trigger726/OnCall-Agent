package org.trigger.opspilot.assistant;

import org.junit.jupiter.api.Test;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.security.SessionAuthorization;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AssistantExecutionManagerTest {
    final SessionAuthorization auth = mock(SessionAuthorization.class);
    final SessionAuthorization.Lease first = new SessionAuthorization.Lease(1, "first", 1, Instant.MAX);
    final SessionAuthorization.Lease queued = new SessionAuthorization.Lease(2, "queued", 1, Instant.MAX);

    @Test void shouldNeverExecuteWorkerAfterAdmissionTransactionFails() throws Exception {
        when(auth.authorized(any(), eq(false))).thenReturn(true);
        var manager = new AssistantExecutionManager(auth, 1, 1, Duration.ofSeconds(10));
        var operations = new AtomicInteger(); var callbacks = new AtomicInteger(); var replacement = new CountDownLatch(1);
        try {
            assertThatThrownBy(() -> manager.submit(first, new AssistantExecutionManager.RequestKey(10, "key"),
                    work -> {
                        long end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                        boolean waiting;
                        do {
                            waiting = Thread.getAllStackTraces().entrySet().stream().anyMatch(entry ->
                                    entry.getKey().getName().equals("opspilot-assistant-1")
                                    && java.util.Arrays.stream(entry.getValue()).anyMatch(frame -> frame.getClassName().equals(AssistantExecutionManager.class.getName())
                                        && frame.getMethodName().startsWith("lambda$submit"))
                                    && java.util.Arrays.stream(entry.getValue()).anyMatch(frame -> frame.getClassName().equals(CountDownLatch.class.getName())
                                        && frame.getMethodName().equals("await")));
                            if (!waiting) try { Thread.sleep(5); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                        } while (!waiting && System.nanoTime() < end);
                        assertThat(waiting).isTrue(); // Force the callable to be inside the gate, not merely cancelled before starting.
                        throw new IllegalStateException("controlled admission transaction rollback");
                    },
                    work -> operations.incrementAndGet(), () -> null, reason -> callbacks.incrementAndGet()))
                    .isInstanceOf(IllegalStateException.class);
            manager.submit(first, work -> replacement.countDown(), reason -> {});
            assertThat(replacement.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(operations).hasValue(0); assertThat(callbacks).hasValue(0);
        } finally { manager.shutdown(); }
    }

    @Test void shouldGateWorkerAndPublishCancellationOnceAfterAdmissionSettles() throws Exception {
        when(auth.authorized(any(), eq(false))).thenReturn(true);
        var manager = new AssistantExecutionManager(auth, 1, 1, Duration.ofSeconds(10));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var operations = new AtomicInteger(); var callbacks = new AtomicInteger();
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var submitted = pool.submit(() -> manager.submit(first, new AssistantExecutionManager.RequestKey(10, "key"), work -> {
                entered.countDown();
                try { assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
            }, work -> operations.incrementAndGet(), () -> null, reason -> {
                assertThat(reason).isEqualTo(AssistantExecutionManager.Reason.CANCELLED); callbacks.incrementAndGet();
            }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(operations).hasValue(0);
            manager.cancel(2, 10, "key"); manager.cancel(1, 11, "key"); manager.cancel(1, 10, "other");
            assertThat(manager.managed()).isEqualTo(1);
            manager.cancel(1, 10, "key"); manager.cancel(1, 10, "key");
            assertThat(callbacks).hasValue(0); assertThat(operations).hasValue(0);
            release.countDown(); submitted.get(2, TimeUnit.SECONDS);
            assertThat(callbacks).hasValue(1); assertThat(operations).hasValue(0);
            assertThat(manager.managed()).isZero();
        } finally { release.countDown(); pool.shutdown(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); manager.shutdown(); }
    }

    @Test void shouldObserveDurableCancellationWithoutInterruptingModel() throws Exception {
        when(auth.authorized(any(), eq(false))).thenReturn(true);
        var manager = new AssistantExecutionManager(auth, 1, 1, Duration.ofSeconds(10));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var ended = new CountDownLatch(1);
        var durable = new AtomicReference<AssistantExecutionManager.Reason>(); var failure = new AtomicReference<Throwable>();
        var stops = new AtomicInteger();
        try {
            manager.submit(first, new AssistantExecutionManager.RequestKey(10, "key"), work -> {}, work -> {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); work.check(); }
                catch (Throwable error) { failure.set(error); }
                finally { ended.countDown(); }
            }, durable::get, reason -> { assertThat(reason).isEqualTo(AssistantExecutionManager.Reason.CANCELLED); stops.incrementAndGet(); });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            durable.set(AssistantExecutionManager.Reason.CANCELLED); manager.reauthorize(); manager.reauthorize();
            assertThat(stops).hasValue(1); assertThat(release.getCount()).isEqualTo(1);
            release.countDown(); assertThat(ended.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("ASSISTANT_REQUEST_CANCELLED"));
        } finally { release.countDown(); manager.shutdown(); }
    }

    @Test void shouldRejectCapacityBeforeCallingAdmission() throws Exception {
        when(auth.authorized(any(), eq(false))).thenReturn(true);
        var manager = new AssistantExecutionManager(auth, 1, 0, Duration.ofSeconds(10));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var admissions = new AtomicInteger();
        try {
            manager.submit(first, work -> { entered.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IllegalStateException(e); } }, reason -> {});
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> manager.submit(first, new AssistantExecutionManager.RequestKey(10, "key"),
                    work -> admissions.incrementAndGet(), work -> {}, () -> null, reason -> {})).isInstanceOf(ApiException.class);
            assertThat(admissions).hasValue(0);
        } finally { release.countDown(); manager.shutdown(); }
    }

    @Test
    void shouldPlaceEnvironmentOverridesUnderTheExecutionManagersConfigurationPrefix() throws Exception {
        var source = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application", new org.springframework.core.io.ClassPathResource("application.yml")).get(0);
        assertThat(source.getProperty("opspilot.assistant.workers")).isEqualTo("${ASSISTANT_WORKERS:4}");
        assertThat(source.getProperty("opspilot.assistant.queue-capacity")).isEqualTo("${ASSISTANT_QUEUE_CAPACITY:16}");
        assertThat(source.getProperty("opspilot.assistant.execution-timeout")).isEqualTo("${ASSISTANT_EXECUTION_TIMEOUT:60s}");
        assertThat(source.getProperty("spring.assistant.workers")).isNull();
    }

    @Test
    void shouldRejectFullQueueAndRemoveRevokedQueuedWorkWithoutInterruptingRunningProvider() throws Exception {
        when(auth.authorized(any(), eq(false))).thenReturn(true);
        var manager = new AssistantExecutionManager(auth, 1, 1, Duration.ofSeconds(10));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        var replacement = new CountDownLatch(1);
        var interrupts = new AtomicInteger();
        var queuedExecutions = new AtomicInteger();
        var stops = new AtomicInteger();
        try {
            manager.submit(first, work -> {
                entered.countDown();
                try { assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { interrupts.incrementAndGet(); }
                finally { ended.countDown(); }
            }, reason -> {});
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            manager.submit(queued, work -> queuedExecutions.incrementAndGet(), reason -> {
                assertThat(reason).isEqualTo(AssistantExecutionManager.Reason.REVOKED);
                stops.incrementAndGet();
            });
            assertThat(manager.queued()).isEqualTo(1);
            assertThatThrownBy(() -> manager.submit(first, work -> queuedExecutions.incrementAndGet(), reason -> {}))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.status().value()).isEqualTo(503);
                        assertThat(error.code()).isEqualTo("ASSISTANT_QUEUE_SATURATED");
                    });
            when(auth.authorized(queued, false)).thenReturn(false);
            manager.reauthorize();
            manager.reauthorize();
            assertThat(manager.queued()).isZero();
            assertThat(manager.managed()).isEqualTo(1);
            assertThat(stops).hasValue(1);
            manager.submit(first, work -> replacement.countDown(), reason -> {});
            assertThat(manager.queued()).isEqualTo(1);
            release.countDown();
            assertThat(ended.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(replacement.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(queuedExecutions).hasValue(0);
            assertThat(interrupts).hasValue(0);
        } finally { release.countDown(); manager.shutdown(); }
    }

    @Test
    void shouldIncludeQueueTimeInBudgetAndStopLateWorkerBeforeAnyMoreAuthorizationQueries() throws Exception {
        when(auth.authorized(any(), eq(false))).thenReturn(true);
        var manager = new AssistantExecutionManager(auth, 1, 1, Duration.ofMillis(250));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        var stops = new AtomicInteger();
        var lateFailure = new AtomicReference<Throwable>();
        var executions = new AtomicInteger();
        try {
            manager.submit(first, work -> {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); work.check(); }
                catch (Throwable error) { lateFailure.set(error); }
                finally { ended.countDown(); }
            }, reason -> { assertThat(reason).isEqualTo(AssistantExecutionManager.Reason.TIMED_OUT); stops.incrementAndGet(); });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            manager.submit(queued, work -> executions.incrementAndGet(), reason -> {
                assertThat(reason).isEqualTo(AssistantExecutionManager.Reason.TIMED_OUT); stops.incrementAndGet();
            });
            Thread.sleep(300);
            manager.reauthorize();
            manager.reauthorize();
            assertThat(stops).hasValue(2);
            assertThat(manager.queued()).isZero();
            assertThat(manager.managed()).isZero();
            clearInvocations(auth);
            release.countDown();
            assertThat(ended.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(lateFailure.get()).isInstanceOf(ApiException.class);
            verifyNoInteractions(auth);
            assertThat(executions).hasValue(0);
        } finally { release.countDown(); manager.shutdown(); }
    }

    @Test
    void shouldFailClosedWhenAuthorizationLookupFails() throws Exception {
        when(auth.authorized(first, false)).thenThrow(new IllegalStateException("sensitive SQL detail"));
        var manager = new AssistantExecutionManager(auth, 1, 0, Duration.ofSeconds(10));
        var closed = new CountDownLatch(1);
        var executions = new AtomicInteger();
        try {
            manager.submit(first, work -> executions.incrementAndGet(), reason -> {
                assertThat(reason).isEqualTo(AssistantExecutionManager.Reason.REVOKED); closed.countDown();
            });
            assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(executions).hasValue(0);
            assertThat(manager.managed()).isZero();
        } finally { manager.shutdown(); }
    }

    @Test
    void shouldCloseTransportWhenWorkerFailsUnexpectedly() throws Exception {
        when(auth.authorized(first, false)).thenReturn(true);
        var manager = new AssistantExecutionManager(auth, 1, 0, Duration.ofSeconds(10));
        var closed = new CountDownLatch(1);
        try {
            manager.submit(first, work -> { throw new LinkageError("sensitive error detail"); }, reason -> {
                assertThat(reason).isEqualTo(AssistantExecutionManager.Reason.FAILED); closed.countDown();
            });
            assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(manager.managed()).isZero();
        } finally { manager.shutdown(); }
    }

    @Test
    void shouldStopOnShutdownWithoutInterruptingWorkerOrTouchingAuthorizationAfterward() throws Exception {
        when(auth.authorized(first, false)).thenReturn(true);
        var manager = new AssistantExecutionManager(auth, 1, 0, Duration.ofSeconds(10));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        var stops = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        try {
            manager.submit(first, work -> {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); work.check(); }
                catch (Throwable error) { failure.set(error); }
                finally { ended.countDown(); }
            }, reason -> { assertThat(reason).isEqualTo(AssistantExecutionManager.Reason.SHUTDOWN); stops.incrementAndGet(); });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            manager.shutdown();
            manager.shutdown();
            assertThat(stops).hasValue(1);
            clearInvocations(auth);
            release.countDown();
            assertThat(ended.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isInstanceOf(ApiException.class).isNotInstanceOf(InterruptedException.class);
            verifyNoInteractions(auth);
        } finally { release.countDown(); manager.shutdown(); }
    }

    @Test
    void shouldRejectInvalidExecutionLimits() {
        assertThatThrownBy(() -> new AssistantExecutionManager(auth, 0, 1, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantExecutionManager(auth, 33, 1, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantExecutionManager(auth, 1, -1, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantExecutionManager(auth, 1, 1025, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantExecutionManager(auth, 1, 1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantExecutionManager(auth, 1, 1, Duration.ofMinutes(6))).isInstanceOf(IllegalArgumentException.class);
    }
}
