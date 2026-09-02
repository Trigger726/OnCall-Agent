package org.trigger.opspilot.investigation;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class AgentExecutionManager {
    private final ThreadPoolTaskExecutor executor;
    private final ScheduledExecutorService deadlineExecutor;
    private final ConcurrentMap<Long, ManagedTask> tasks = new ConcurrentHashMap<>();

    public AgentExecutionManager(
            @Qualifier("agentTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.executor = executor;
        this.deadlineExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "opspilot-agent-deadline");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void submit(long runId, LocalDateTime deadlineAt, Runnable runnable, Runnable onDeadline) {
        ManagedTask managed = new ManagedTask();
        managed.future = new FutureTask<>(() -> {
            try {
                runnable.run();
            } finally {
                remove(runId, managed);
            }
            return null;
        });
        if (tasks.putIfAbsent(runId, managed) != null) {
            throw new IllegalStateException("Agent run " + runId + " is already scheduled");
        }

        long delayMillis = Math.max(0, Duration.between(LocalDateTime.now(), deadlineAt).toMillis());
        managed.deadline = deadlineExecutor.schedule(() -> {
            if (tasks.get(runId) != managed) return;
            try {
                onDeadline.run();
            } finally {
                cancel(runId);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);

        try {
            executor.execute(managed.future);
        } catch (RuntimeException exception) {
            remove(runId, managed);
            managed.future.cancel(false);
            throw exception;
        }
    }

    public boolean cancel(long runId) {
        ManagedTask managed = tasks.remove(runId);
        if (managed == null) return false;
        if (managed.deadline != null) managed.deadline.cancel(false);
        return managed.future.cancel(true);
    }

    public boolean isActive(long runId) {
        return tasks.containsKey(runId);
    }

    private void remove(long runId, ManagedTask managed) {
        if (!tasks.remove(runId, managed)) return;
        if (managed.deadline != null) managed.deadline.cancel(false);
    }

    @PreDestroy
    void shutdownDeadlineExecutor() {
        deadlineExecutor.shutdownNow();
    }

    private static final class ManagedTask {
        private FutureTask<Void> future;
        private ScheduledFuture<?> deadline;
    }
}
