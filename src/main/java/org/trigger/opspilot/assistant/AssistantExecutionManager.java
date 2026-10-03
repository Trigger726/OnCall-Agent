package org.trigger.opspilot.assistant;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.stereotype.Component;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.security.SessionAuthorization;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded transport work. A stopped model call may occupy its worker until its own I/O budget ends. */
@Component
public class AssistantExecutionManager {
    private static final Logger log = LoggerFactory.getLogger(AssistantExecutionManager.class);
    private final SessionAuthorization authorization;
    private final ThreadPoolExecutor executor;
    private final Duration timeout;
    private final ConcurrentMap<FutureTask<Void>, Work> tasks = new ConcurrentHashMap<>();

    public AssistantExecutionManager(SessionAuthorization authorization,
            @Value("${opspilot.assistant.workers:4}") int workers,
            @Value("${opspilot.assistant.queue-capacity:16}") int capacity,
            @Value("${opspilot.assistant.execution-timeout:60s}") Duration timeout) {
        if (workers < 1 || workers > 32 || capacity < 0 || capacity > 1024
                || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid assistant execution limits");
        }
        this.authorization = authorization;
        this.timeout = timeout;
        var sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                capacity == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(capacity), runnable -> {
                    var thread = new Thread(runnable, "opspilot-assistant-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public void submit(SessionAuthorization.Lease lease, Consumer<Work> operation, Consumer<Reason> onStop) {
        submit(lease, null, work -> {}, operation, () -> null, onStop);
    }

    /** Reserve actual bounded capacity before persisting admission; no worker can enter preparation before it commits. */
    public void submit(SessionAuthorization.Lease lease, RequestKey key, Consumer<Work> admission,
                       Consumer<Work> operation, Supplier<Reason> durableReason, Consumer<Reason> onStop) {
        var work = new Work(lease, key, durableReason, onStop);
        work.future = new FutureTask<>(() -> {
            try { work.ready.await(); work.check(); operation.accept(work); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); work.stop(Reason.SHUTDOWN); }
            catch (CredentialsExpiredException ignored) { work.stop(Reason.REVOKED); }
            catch (RuntimeException | Error exception) {
                if (work.reason.get() == null) {
                    log.warn("Assistant work stopped: {}", exception.getClass().getSimpleName());
                    work.stop(Reason.FAILED);
                }
            }
            finally { tasks.remove(work.future, work); }
            return null;
        });
        tasks.put(work.future, work);
        try {
            executor.execute(work.future);
            if (work.future.isCancelled()) executor.remove(work.future);
        } catch (RejectedExecutionException exception) {
            tasks.remove(work.future, work);
            work.future.cancel(false);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_QUEUE_SATURATED", "助手队列已满，请稍后重试");
        }
        try { admission.accept(work); }
        catch (RuntimeException | Error error) {
            work.reason.compareAndSet(null, Reason.FAILED); // cancel(false) does not stop a callable already inside the admission gate.
            work.notified.set(true); // The original request receives this admission exception, not an unrelated stop callback.
            tasks.remove(work.future, work);
            work.future.cancel(false);
            executor.remove(work.future);
            throw error;
        } finally {
            work.admitted = true;
            work.ready.countDown();
            work.notifyStopped(); // A stop during the admission transaction is published only after that transaction settles.
        }
    }

    public void cancel(long ownerId, long sessionId, String keyHash) {
        var key = new RequestKey(sessionId, keyHash);
        tasks.values().forEach(work -> {
            if (work.lease.userId() == ownerId && key.equals(work.key)) work.stop(Reason.CANCELLED);
        });
    }

    @Scheduled(fixedDelayString = "${opspilot.assistant.authorization-check-delay:1000}", scheduler = "agentSubscriptionScheduler")
    public void reauthorize() {
        tasks.values().forEach(work -> {
            try { work.check(); }
            catch (CredentialsExpiredException | ApiException ignored) { /* The stop callback already settled the transport. */ }
        });
    }

    int queued() { return executor.getQueue().size(); }
    int managed() { return tasks.size(); }

    @PreDestroy
    void shutdown() {
        tasks.values().forEach(work -> work.stop(Reason.SHUTDOWN));
        // Do not interrupt a cold executable-JAR HTTP class load.
        executor.shutdown();
    }

    public enum Reason { REVOKED, TIMED_OUT, CANCELLED, SUPERSEDED, SHUTDOWN, FAILED }
    public record RequestKey(long sessionId, String keyHash) { }

    public final class Work {
        private final SessionAuthorization.Lease lease;
        private final RequestKey key;
        private final Supplier<Reason> durableReason;
        private final Consumer<Reason> onStop;
        private final CountDownLatch ready = new CountDownLatch(1);
        private final AtomicBoolean notified = new AtomicBoolean();
        private volatile boolean admitted;
        private final long deadline = System.nanoTime() + timeout.toNanos();
        private final long deadlineEpochMs = System.currentTimeMillis() + timeout.toMillis();
        private final AtomicReference<Reason> reason = new AtomicReference<>();
        private FutureTask<Void> future;
        private Work(SessionAuthorization.Lease lease, RequestKey key, Supplier<Reason> durableReason, Consumer<Reason> onStop) {
            this.lease = lease; this.key = key; this.durableReason = durableReason; this.onStop = onStop;
        }
        public long deadlineEpochMillis() { return deadlineEpochMs; }
        public void check() {
            if (reason.get() == null && System.nanoTime() - deadline >= 0) stop(Reason.TIMED_OUT);
            if (reason.get() == null) {
                boolean valid;
                try { valid = authorization.authorized(lease, false); }
                catch (RuntimeException exception) { valid = false; }
                if (!valid) stop(Reason.REVOKED);
            }
            if (reason.get() == null && admitted) {
                try { var persisted = durableReason.get(); if (persisted != null) stop(persisted); }
                catch (RuntimeException unavailable) { stop(Reason.FAILED); }
            }
            if (reason.get() == Reason.REVOKED) throw new CredentialsExpiredException("Assistant session expired");
            if (reason.get() == Reason.CANCELLED) throw new ApiException(HttpStatus.CONFLICT,
                    "ASSISTANT_REQUEST_CANCELLED", "回答已取消，原请求不会重新执行");
            if (reason.get() == Reason.SUPERSEDED) throw new ApiException(HttpStatus.CONFLICT,
                    "ASSISTANT_REQUEST_SUPERSEDED", "原问题已清空，回答不会重新执行");
            if (reason.get() != null) throw new ApiException(HttpStatus.GATEWAY_TIMEOUT,
                    "ASSISTANT_EXECUTION_STOPPED", "回答已停止，请重新发送问题");
        }
        private void stop(Reason value) {
            if (!tasks.remove(future, this) || !reason.compareAndSet(null, value)) return;
            future.cancel(false);
            executor.remove(future);
            notifyStopped();
        }
        private void notifyStopped() {
            if (!admitted || reason.get() == null || !notified.compareAndSet(false, true)) return;
            try { onStop.accept(reason.get()); }
            catch (RuntimeException ignored) { /* Cannot write to a closed transport. */ }
        }
    }
}
