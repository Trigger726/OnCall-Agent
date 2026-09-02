package org.trigger.opspilot.investigation;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutionManagerTest {
    @Test
    void shouldExposeSaturationAndInterruptActiveTask() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.initialize();
        AgentExecutionManager manager = new AgentExecutionManager(executor);
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
        AgentExecutionManager manager = new AgentExecutionManager(executor);
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch activeRelease = new CountDownLatch(1);
        CountDownLatch queuedStarted = new CountDownLatch(1);
        CountDownLatch rejectedStarted = new CountDownLatch(1);

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
            activeRelease.countDown();
            assertThat(queuedStarted.await(300, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            activeRelease.countDown();
            manager.cancel(201);
            manager.cancel(202);
            manager.shutdownDeadlineExecutor();
            executor.shutdown();
        }
    }
}
