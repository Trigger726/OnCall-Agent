package org.trigger.opspilot.investigation;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.trigger.opspilot.common.ApiException;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AgentEventSubscriptions {
    private static final Set<String> TERMINAL = Set.of("RUN_COMPLETED", "RUN_FAILED",
            "RUN_CANCELLED", "RUN_TIMED_OUT", "RUN_REJECTED");
    private final AgentRunEventService events;
    private final Executor executor;
    private final Semaphore slots = new Semaphore(128);
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();

    public AgentEventSubscriptions(AgentRunEventService events,
            @Qualifier("agentSubscriptionExecutor") Executor executor) {
        this.events = events;
        this.executor = executor;
    }

    public SseEmitter open(long runId, long after) {
        var emitter = new SseEmitter(60_000L);
        subscribe(runId, after, new Output() {
            public void send(AgentRunEventService.EventView event) throws IOException {
                emitter.send(SseEmitter.event().id(Long.toString(event.id()))
                        .name(event.eventType().toLowerCase(Locale.ROOT)).reconnectTime(2000).data(event));
            }
            public void complete() { emitter.complete(); }
            public void onClose(Runnable callback) {
                emitter.onCompletion(callback);
                emitter.onTimeout(callback);
                emitter.onError(error -> callback.run());
            }
        });
        return emitter;
    }

    void subscribe(long runId, long after, Output output) {
        // Same visibility as JSON replay; reject a cursor belonging to a different run.
        var cursorEvent = events.validateCursor(runId, after);
        if (!slots.tryAcquire()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "AGENT_SUBSCRIPTIONS_FULL", "实时订阅已满，请稍后携带游标重试");
        var subscription = new Subscription(runId, after, output,
                cursorEvent != null && TERMINAL.contains(cursorEvent.eventType()));
        subscriptions.add(subscription); // Register before the first database catch-up.
        output.onClose(subscription::close);
        subscription.signal();
    }

    @EventListener
    public void notified(AgentEventReceiver.Notification notification) {
        subscriptions.forEach(s -> { if (s.runId == notification.runId()) s.signal(); });
    }

    @Scheduled(fixedDelayString = "${opspilot.agent.events.catchup-delay:5000}",
            initialDelayString = "${opspilot.agent.events.catchup-initial-delay:0}",
            scheduler = "agentSubscriptionScheduler")
    public void catchUp() {
        // Also covers absent/lost/trimmed Redis notifications and default standalone mode.
        subscriptions.forEach(Subscription::signal);
    }

    int size() { return subscriptions.size(); }

    interface Output {
        void send(AgentRunEventService.EventView event) throws IOException;
        void complete();
        void onClose(Runnable callback);
    }

    private final class Subscription {
        private final long runId;
        private final Output output;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean dirty = new AtomicBoolean();
        private long cursor;
        private int sent;
        private boolean terminal;

        Subscription(long runId, long cursor, Output output, boolean terminal) {
            this.runId = runId;
            this.cursor = cursor;
            this.output = output;
            this.terminal = terminal;
        }

        void signal() {
            dirty.set(true);
            if (closed.get() || !scheduled.compareAndSet(false, true)) return;
            try {
                executor.execute(this::drain);
            } catch (RejectedExecutionException exception) {
                // Keep dirty for periodic retry; never execute a slow send on the caller.
                scheduled.set(false);
            }
        }

        void drain() {
            try {
                dirty.set(false);
                if (closed.get()) return;
                if (terminal) { finish(); return; }
                var batch = events.page(runId, cursor, 100);
                for (var event : batch) {
                    if (closed.get()) return;
                    output.send(event);
                    cursor = event.id();
                    sent++;
                    terminal = TERMINAL.contains(event.eventType());
                    // Also bounds SseEmitter's pre-initialization buffer. Resume via cursor.
                    if (terminal || sent >= 1000) { finish(); return; }
                }
                if (batch.size() == 100) dirty.set(true);
            } catch (IOException exception) {
                // The servlet container owns completion after a failed socket write.
                close();
            } catch (RuntimeException exception) {
                finish(); // DB/send failure closes this stream; durable cursor permits retry.
            } finally {
                scheduled.set(false);
                if (dirty.get() && !closed.get()) signal();
            }
        }

        void finish() {
            close();
            output.complete();
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                subscriptions.remove(this);
                slots.release();
            }
        }
    }
}
