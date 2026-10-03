package org.trigger.opspilot.assistant;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.trigger.opspilot.common.ApiException;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Blocking servlet writes never run on the model worker or the shared stop scheduler. */
@Component
public class AssistantStreamTransport {
    private final ThreadPoolExecutor writers;
    private final Set<Transport> active = ConcurrentHashMap.newKeySet();

    public AssistantStreamTransport(@Value("${opspilot.assistant.stream-writers:4}") int workers,
            @Value("${opspilot.assistant.stream-queue-capacity:16}") int capacity) {
        if (workers < 1 || workers > 32 || capacity < 0 || capacity > 1024) {
            throw new IllegalArgumentException("Invalid assistant stream transport limits");
        }
        var sequence = new AtomicInteger();
        writers = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                capacity == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(capacity), operation -> {
                    var thread = new Thread(operation, "opspilot-assistant-output-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    Transport open(SseEmitter emitter, Runnable authorization) {
        var transport = new Transport(emitter, authorization);
        active.add(transport);
        try { writers.execute(transport); }
        catch (RejectedExecutionException rejected) {
            transport.disconnect(); active.remove(transport);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_STREAM_SATURATED", "助手输出队列已满，请稍后重试");
        }
        return transport;
    }

    int managed() { return active.size(); }
    int queued() { return writers.getQueue().size(); }

    @PreDestroy void shutdown() {
        active.forEach(Transport::disconnect);
        writers.shutdown(); // The servlet container closes its sockets; never interrupt cold executable-JAR loads.
    }

    final class Transport implements Runnable {
        private final SseEmitter emitter;
        private final Runnable authorization;
        private final ArrayBlockingQueue<Write> pending = new ArrayBlockingQueue<>(1);
        private volatile boolean accepting = true;
        private volatile boolean connected = true;
        private volatile AssistantController.StreamEvent terminal;
        private boolean preview;

        private Transport(SseEmitter emitter, Runnable authorization) {
            this.emitter = emitter; this.authorization = authorization;
            emitter.onCompletion(this::disconnect);
            emitter.onTimeout(this::disconnect);
            emitter.onError(error -> disconnect());
        }

        synchronized void disconnect() {
            accepting = false; connected = false; pending.clear();
            if (writers.remove(this)) active.remove(this);
        }

        synchronized void stopped(String type, String content) {
            if (!accepting) return;
            terminal = type == null ? null : new AssistantController.StreamEvent(type, content, null, null);
            accepting = false;
            pending.clear(); // No queued preview can outlive a stop; an already buffered network byte cannot be recalled.
        }

        void delta(String chunk, Runnable checkpoint) {
            checkpoint.run();
            boolean first = !preview; preview = true;
            write(() -> {
                if (first) emitter.send(SseEmitter.event().name("generation")
                        .data(new AssistantController.StreamEvent("generation", "NATIVE", null, null)));
                checkpoint.run();
                emitter.send(SseEmitter.event().name("token").data(new AssistantController.StreamEvent("token", chunk, null, null)));
            }, checkpoint, true);
        }

        void completed(AssistantService.MessageView message, Runnable checkpoint) {
            checkpoint.run();
            write(() -> {
                if (preview) emitter.send(SseEmitter.event().name("done")
                        .data(new AssistantController.StreamEvent("done", "", message.id(), message.evidenceJson())));
                else saved(message, checkpoint);
            }, checkpoint, true);
            stopped(null, null);
        }

        /** A replay has already committed; the request thread must return the emitter, not wait for servlet initialization. */
        void replay(AssistantService.MessageView message, Runnable checkpoint) {
            // saved() checks the original lease before every event; keep that check inside the closing finally.
            write(() -> { try { saved(message, checkpoint); } finally { stopped(null, null); } }, () -> {}, false);
        }

        private void saved(AssistantService.MessageView message, Runnable checkpoint) throws IOException {
            checkpoint.run();
            emitter.send(SseEmitter.event().name("meta")
                    .data(new AssistantController.StreamEvent("meta", "", message.id(), message.evidenceJson())));
            String content = message.content() == null ? "" : message.content();
            for (int start = 0; start < content.length(); start += 28) {
                checkpoint.run();
                emitter.send(SseEmitter.event().name("message")
                        .data(new AssistantController.StreamEvent("delta", content.substring(start, Math.min(content.length(), start + 28)), message.id(), null)));
            }
            checkpoint.run();
            emitter.send(SseEmitter.event().name("done")
                    .data(new AssistantController.StreamEvent("done", "", message.id(), message.evidenceJson())));
        }

        private void write(Output operation, Runnable checkpoint, boolean await) {
            var write = new Write(operation, checkpoint, new CompletableFuture<>());
            synchronized (this) {
                if (!accepting || !connected) return;
                if (!pending.offer(write)) { stopped(null, null); return; }
            }
            if (!await) return;
            while (true) {
                checkpoint.run();
                if (!accepting || !connected) return;
                try { write.finished.get(100, TimeUnit.MILLISECONDS); checkpoint.run(); return; }
                catch (TimeoutException waiting) { /* Keep the ORIGINAL budget, lease and SQL stop fences while the write is blocked. */ }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Assistant output wait interrupted", interrupted); }
                catch (ExecutionException failed) {
                    if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
                    throw new IllegalStateException("Assistant output failed", failed.getCause());
                }
            }
        }

        @Override public void run() {
            try {
                while (accepting || !pending.isEmpty()) {
                    var write = pending.poll(100, TimeUnit.MILLISECONDS);
                    if (write == null) continue;
                    try {
                        if (connected && accepting) { write.checkpoint.run(); write.operation.run(); }
                        write.finished.complete(null);
                    } catch (IOException | IllegalStateException disconnected) {
                        disconnect(); write.finished.complete(null);
                    } catch (RuntimeException | Error failure) { write.finished.completeExceptionally(failure); }
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); disconnect(); }
            finally {
                if (connected) {
                    try {
                        var event = terminal;
                        if (event != null) { authorization.run(); emitter.send(SseEmitter.event().name(event.type()).data(event)); }
                    } catch (IOException | IllegalStateException disconnected) { connected = false; }
                    catch (RuntimeException unauthorized) { /* No terminal payload under a revoked original lease. */ }
                    finally { if (connected) emitter.complete(); }
                }
                active.remove(this);
            }
        }
    }

    @FunctionalInterface private interface Output { void run() throws IOException; }
    private record Write(Output operation, Runnable checkpoint, CompletableFuture<Void> finished) { }
}
