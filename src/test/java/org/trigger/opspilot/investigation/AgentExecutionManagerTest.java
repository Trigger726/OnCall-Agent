package org.trigger.opspilot.investigation;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import io.micrometer.tracing.Tracer;
import org.trigger.opspilot.observability.tracing.OpsPilotTracing;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutionManagerTest {
    @Test
    void shouldReleaseRevokedQueuedSlotWhileAnotherWorkerRemainsBlocked() throws Exception {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(1); executor.initialize();
        var manager = new AgentExecutionManager(executor, OpsPilotTracing.using(Tracer.NOOP));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var staleStarted = new CountDownLatch(1);
        var replacementStarted = new CountDownLatch(1);
        var valid = new java.util.concurrent.atomic.AtomicBoolean(true);
        var denied = new java.util.concurrent.atomic.AtomicInteger();
        try {
            manager.submit(301, LocalDateTime.now().plusSeconds(30), () -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }, () -> {});
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            manager.submit(302, LocalDateTime.now().plusSeconds(30), staleStarted::countDown, () -> {}, valid::get, denied::incrementAndGet);
            valid.set(false);
            manager.reauthorize();
            manager.reauthorize();
            assertThat(denied).hasValue(1);
            assertThat(manager.isActive(302)).isFalse();
            manager.submit(303, LocalDateTime.now().plusSeconds(30), replacementStarted::countDown, () -> {});
            assertThat(started.getCount()).isZero();
            assertThat(staleStarted.getCount()).isEqualTo(1);
            assertThat(replacementStarted.getCount()).isEqualTo(1);
            release.countDown();
            assertThat(replacementStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(staleStarted.await(200, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            release.countDown(); manager.cancel(301); manager.cancel(302); manager.cancel(303);
            manager.shutdownDeadlineExecutor(); executor.shutdown();
        }
    }

    @Test
    void shouldFailClosedWithoutInterruptingActiveAuthenticatedWorker() throws Exception {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(0); executor.initialize();
        var manager = new AgentExecutionManager(executor, OpsPilotTracing.using(Tracer.NOOP));
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var checkFails = new java.util.concurrent.atomic.AtomicBoolean();
        var denied = new java.util.concurrent.atomic.AtomicInteger();
        try {
            manager.submit(401, LocalDateTime.now().plusSeconds(30), () -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); interrupted.countDown(); }
                finally { finished.countDown(); }
            }, () -> {}, () -> {
                if (checkFails.get()) throw new IllegalStateException("authorization read unavailable");
                return true;
            }, denied::incrementAndGet);
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            checkFails.set(true);
            manager.reauthorize();
            assertThat(interrupted.await(100, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(finished.getCount()).isEqualTo(1);
            assertThat(manager.isActive(401)).isFalse();
            assertThat(denied).hasValue(1);
            release.countDown();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); manager.cancel(401); manager.shutdownDeadlineExecutor(); executor.shutdown(); }
    }

    @Test
    void shouldExposeSaturationAndInterruptActiveTask() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.initialize();
        AgentExecutionManager manager = new AgentExecutionManager(
                executor, OpsPilotTracing.using(Tracer.NOOP));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        try {
            manager.submit(101, LocalDateTime.now().plusSeconds(30), () -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    interrupted.countDown();
                }
            }, () -> { });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> manager.submit(102, LocalDateTime.now().plusSeconds(30),
                    () -> { }, () -> { }))
                    .isInstanceOf(TaskRejectedException.class);

            assertThat(manager.cancel(101)).isTrue();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(manager.isActive(101)).isFalse();
        } finally {
            manager.shutdownDeadlineExecutor();
            executor.shutdown();
        }
    }

    @Test
    void shouldRejectBeyondRunningAndQueuedCapacityWithoutStartingRejectedTask() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        AgentExecutionManager manager = new AgentExecutionManager(
                executor, OpsPilotTracing.using(Tracer.NOOP));
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch activeRelease = new CountDownLatch(1);
        CountDownLatch queuedStarted = new CountDownLatch(1);
        CountDownLatch rejectedStarted = new CountDownLatch(1);
        CountDownLatch replacementStarted = new CountDownLatch(1);

        try {
            manager.submit(201, LocalDateTime.now().plusSeconds(30), () -> {
                activeStarted.countDown();
                try {
                    activeRelease.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }, () -> { });
            assertThat(activeStarted.await(2, TimeUnit.SECONDS)).isTrue();

            manager.submit(202, LocalDateTime.now().plusSeconds(30), queuedStarted::countDown, () -> { });
            assertThat(manager.isActive(202)).isTrue();
            assertThatThrownBy(() -> manager.submit(203, LocalDateTime.now().plusSeconds(30),
                    rejectedStarted::countDown, () -> { }))
                    .isInstanceOf(TaskRejectedException.class);

            assertThat(manager.isActive(203)).isFalse();
            assertThat(rejectedStarted.getCount()).isEqualTo(1);
            assertThat(manager.cancel(202)).isTrue();
            manager.submit(204, LocalDateTime.now().plusSeconds(30),
                    replacementStarted::countDown, () -> { });
            assertThat(replacementStarted.getCount()).isEqualTo(1);
            activeRelease.countDown();
            assertThat(replacementStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(queuedStarted.await(300, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            activeRelease.countDown();
            manager.cancel(201);
            manager.cancel(202);
            manager.cancel(204);
            manager.shutdownDeadlineExecutor();
            executor.shutdown();
        }
    }
}
