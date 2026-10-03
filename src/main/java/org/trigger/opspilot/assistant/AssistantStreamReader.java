package org.trigger.opspilot.assistant;

import org.reactivestreams.Subscription;
import reactor.core.Exceptions;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Consumes on the existing bounded worker; idle provider streams must still observe stop fences. */
final class AssistantStreamReader {
    private AssistantStreamReader() { }

    static String read(Flux<String> source, Runnable checkpoint, Consumer<String> onDelta) {
        checkpoint.run();
        var subscriber = new ReaderSubscriber();
        var content = new StringBuilder();
        try {
            source.subscribe(subscriber);
            while (true) {
                checkpoint.run();
                String chunk = subscriber.chunks.poll(100, TimeUnit.MILLISECONDS);
                checkpoint.run();
                if (subscriber.failure != null) throw Exceptions.propagate(subscriber.failure);
                if (chunk != null) {
                    if (chunk.length() > AssistantAiService.MAX_STREAM_CHARACTERS - content.length()) {
                        throw new IllegalStateException("Assistant native answer exceeds bounded size");
                    }
                    onDelta.accept(chunk);
                    content.append(chunk);
                    subscriber.request(1);
                } else if (subscriber.finished) {
                    if (content.toString().isBlank()) throw new IllegalStateException("Assistant native answer is empty");
                    return content.toString();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Assistant stream worker interrupted", interrupted);
        } finally {
            subscriber.dispose();
        }
    }

    private static final class ReaderSubscriber extends BaseSubscriber<String> {
        private final ArrayBlockingQueue<String> chunks = new ArrayBlockingQueue<>(1);
        private volatile boolean finished;
        private volatile Throwable failure;

        @Override protected void hookOnSubscribe(Subscription subscription) { request(1); }
        @Override protected void hookOnNext(String chunk) {
            if (!chunks.offer(chunk)) {
                failure = new IllegalStateException("Assistant native publisher exceeded demand");
                finished = true;
                cancel();
            }
        }
        @Override protected void hookOnComplete() { finished = true; }
        @Override protected void hookOnError(Throwable error) { failure = error; finished = true; }
    }
}
