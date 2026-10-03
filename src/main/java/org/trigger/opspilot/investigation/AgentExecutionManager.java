package org.trigger.opspilot.investigation;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.trigger.opspilot.observability.tracing.OpsPilotTracing;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@Component
public class AgentExecutionManager {
    private final ThreadPoolTaskExecutor executor;
    private final OpsPilotTracing tracing;
    private final ScheduledExecutorService deadlineExecutor;
    private final ConcurrentMap<Long, ManagedTask> tasks = new ConcurrentHashMap<>();

    public AgentExecutionManager(
            @Qualifier("agentTaskExecutor") ThreadPoolTaskExecutor executor,
            OpsPilotTracing tracing) {
        this.executor = executor;
        this.tracing = tracing;
        this.deadlineExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "opspilot-agent-deadline");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void submit(long runId, LocalDateTime deadlineAt, Runnable runnable, Runnable onDeadline) {
        submit(runId, deadlineAt, runnable, onDeadline, null, null);
    }

    public void submit(long runId, LocalDateTime deadlineAt, Runnable runnable, Runnable onDeadline,
                       BooleanSupplier authorized, Runnable onAuthorizationLost) {
        if (authorized != null && onAuthorizationLost == null) throw new IllegalArgumentException("Authorization termination callback required");
        ManagedTask managed = new ManagedTask();
        managed.authorized = authorized;
        managed.onAuthorizationLost = onAuthorizationLost;
        Runnable tracedRunnable = tracing.capture(runnable);
        managed.future = new FutureTask<>(() -> {
            try {
                tracedRunnable.run();
            } catch (Error error) {
                String missingClass = error instanceof NoClassDefFoundError && error.getMessage() != null
                        && error.getMessage().matches("[A-Za-z0-9_.$/]+") ? error.getMessage() : "not-recorded";
                org.slf4j.LoggerFactory.getLogger(AgentExecutionManager.class).warn(
                        "Agent run {} escaped with JVM error: {} / {}", runId, error.getClass().getSimpleName(), missingClass);
                throw error;
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
                cancel(runId, managed);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);

        try {
            executor.execute(managed.future);
            // Cancellation can happen before execute has placed the task in the queue.
            if (managed.future.isCancelled()) {
                executor.getThreadPoolExecutor().remove(managed.future);
            }
        } catch (RuntimeException exception) {
            remove(runId, managed);
            managed.future.cancel(false);
            throw exception;
        }
    }

    public boolean cancel(long runId) {
        ManagedTask managed = tasks.get(runId);
        return managed != null && cancel(runId, managed);
    }

    private boolean cancel(long runId, ManagedTask managed) {
        if (!tasks.remove(runId, managed)) return false;
        if (managed.deadline != null) managed.deadline.cancel(false);
        // Authenticated production work uses durable control fences and bounded Provider reads.
        // Interrupting a cold executable-JAR HTTP class load can poison it for subsequent runs.
        boolean cancelled = managed.future.cancel(managed.authorized == null);
        executor.getThreadPoolExecutor().remove(managed.future);
        return cancelled;
    }

    public boolean isActive(long runId) {
        return tasks.containsKey(runId);
    }

    @Scheduled(fixedDelayString = "${opspilot.agent.authorization-check-delay:1000}",
            scheduler = "agentSubscriptionScheduler")
    public void reauthorize() {
        tasks.forEach((runId, managed) -> {
            if (managed.authorized == null || tasks.get(runId) != managed) return;
            boolean valid;
            try { valid = managed.authorized.getAsBoolean(); }
            catch (RuntimeException exception) { valid = false; } // Cannot prove authorization: stop, never continue.
            if (valid) return;
            try { managed.onAuthorizationLost.run(); }
            catch (RuntimeException exception) {
                org.slf4j.LoggerFactory.getLogger(AgentExecutionManager.class)
                        .warn("Authorization termination could not be persisted for run {}; deadline recovery remains required", runId);
            } finally { cancel(runId, managed); }
        });
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
        private BooleanSupplier authorized;
        private Runnable onAuthorizationLost;
    }
}
