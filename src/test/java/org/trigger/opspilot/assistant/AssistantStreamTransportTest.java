package org.trigger.opspilot.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.trigger.opspilot.common.ApiException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AssistantStreamTransportTest {
    @Test void shouldPreserveNativeOrderAndPublishOnlyTheCommittedAnswerId() throws Exception {
        var manager = new AssistantStreamTransport(1, 1);
        var emitter = mock(SseEmitter.class);
        var events = new CopyOnWriteArrayList<AssistantController.StreamEvent>();
        doAnswer(call -> {
            var builder = call.getArgument(0, SseEmitter.SseEventBuilder.class);
            builder.build().stream().map(ResponseBodyEmitter.DataWithMediaType::getData)
                    .filter(AssistantController.StreamEvent.class::isInstance)
                    .map(AssistantController.StreamEvent.class::cast).forEach(events::add);
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        try {
            var transport = manager.open(emitter, () -> {});
            transport.delta("  first", () -> {}); transport.delta("🙂\n", () -> {});
            transport.completed(new AssistantService.MessageView(7L, "ASSISTANT", "  first🙂\n", "{}", LocalDateTime.now()), () -> {});
            until(() -> manager.managed() == 0);
            assertThat(events.stream().map(AssistantController.StreamEvent::type)).containsExactly("generation", "token", "token", "done");
            assertThat(events.subList(0, 3)).allSatisfy(e -> { assertThat(e.messageId()).isNull(); assertThat(e.evidenceJson()).isNull(); });
            assertThat(events.get(3).messageId()).isEqualTo(7L);
            verify(emitter, times(1)).complete();
        } finally { close(manager); }
    }

    @Test void shouldCheckOriginalFencesWhileWriteIsBlockedAndStopWithoutWaitingForItsLock() throws Exception {
        var manager = new AssistantStreamTransport(1, 1);
        var emitter = mock(SseEmitter.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var stop = new AtomicBoolean(); var callerChecks = new AtomicInteger();
        doAnswer(call -> { entered.countDown(); if (!release.await(4, TimeUnit.SECONDS)) throw new IOException("controlled write deadline"); return null; })
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        var callers = Executors.newFixedThreadPool(2);
        try {
            var transport = manager.open(emitter, () -> {});
            var delta = callers.submit(() -> transport.delta("preview", () -> {
                if (!Thread.currentThread().getName().startsWith("opspilot-assistant-output-")) callerChecks.incrementAndGet();
                if (stop.get()) throw new CredentialsExpiredException("original lease revoked");
            }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            until(() -> callerChecks.get() >= 4); // Not merely one check before an unbounded Future.get().
            stop.set(true);
            callers.submit(() -> transport.stopped(null, null)).get(300, TimeUnit.MILLISECONDS);
            assertThatThrownBy(() -> delta.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CredentialsExpiredException.class);
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(manager.managed()).isEqualTo(1); // The finite output thread is still blocked, not silently counted as released.
            verify(emitter, never()).complete();
        } finally {
            release.countDown(); callers.shutdown(); assertThat(callers.awaitTermination(3, TimeUnit.SECONDS)).isTrue(); close(manager);
        }
    }

    @Test void shouldBoundReservedWritersAndRejectBeforeAnyModelAdmission() throws Exception {
        var manager = new AssistantStreamTransport(1, 0);
        try {
            var first = manager.open(mock(SseEmitter.class), () -> {});
            assertThatThrownBy(() -> manager.open(mock(SseEmitter.class), () -> {})).isInstanceOfSatisfying(ApiException.class, failure -> {
                assertThat(failure.status().value()).isEqualTo(503); assertThat(failure.code()).isEqualTo("ASSISTANT_STREAM_SATURATED");
            });
            assertThat(manager.managed()).isEqualTo(1); assertThat(manager.queued()).isZero();
            first.disconnect(); until(() -> manager.managed() == 0);
            var replacement = manager.open(mock(SseEmitter.class), () -> {}); replacement.disconnect();
        } finally { close(manager); }
    }

    @Test void shouldRemoveAnAbortedReservationFromTheBoundedWriterQueue() throws Exception {
        var manager = new AssistantStreamTransport(1, 1);
        try {
            var first = manager.open(mock(SseEmitter.class), () -> {});
            var queued = manager.open(mock(SseEmitter.class), () -> {});
            assertThat(manager.queued()).isEqualTo(1);
            assertThatThrownBy(() -> manager.open(mock(SseEmitter.class), () -> {})).isInstanceOf(ApiException.class);
            queued.disconnect(); assertThat(manager.queued()).isZero(); assertThat(manager.managed()).isEqualTo(1);
            var replacement = manager.open(mock(SseEmitter.class), () -> {});
            assertThat(manager.queued()).isEqualTo(1); replacement.disconnect(); first.disconnect();
        } finally { close(manager); }
    }

    @Test void disconnectedBrowserShouldNotCancelTheDurableWorkerOrSkipItsCheckpoints() throws Exception {
        var manager = new AssistantStreamTransport(1, 1);
        var emitter = mock(SseEmitter.class); var checks = new AtomicInteger();
        doThrow(new IOException("controlled browser disconnect")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        try {
            var transport = manager.open(emitter, () -> {});
            transport.delta("first", checks::incrementAndGet);
            transport.delta("second", checks::incrementAndGet);
            transport.completed(new AssistantService.MessageView(8L, "ASSISTANT", "firstsecond", null, LocalDateTime.now()), checks::incrementAndGet);
            until(() -> manager.managed() == 0); assertThat(checks.get()).isGreaterThanOrEqualTo(3);
            verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter, never()).complete(); // IOException cleanup belongs to the servlet container.
        } finally { close(manager); }
    }

    @Test void terminalEventMustRecheckOriginalLeaseRatherThanReuseAnEarlierAuthorizationResult() throws Exception {
        var manager = new AssistantStreamTransport(1, 1); var emitter = mock(SseEmitter.class);
        try {
            var transport = manager.open(emitter, () -> { throw new CredentialsExpiredException("revoked while terminal was queued"); });
            transport.stopped("cancelled", "cancelled"); until(() -> manager.managed() == 0);
            verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class)); verify(emitter).complete();
        } finally { close(manager); }
    }

    @Test void rejectedReplayLeaseShouldCloseItsWriterWithoutLeakingAnIdleReservation() throws Exception {
        var manager = new AssistantStreamTransport(1, 1); var emitter = mock(SseEmitter.class);
        try {
            manager.open(emitter, () -> {}).replay(new AssistantService.MessageView(9L, "ASSISTANT", "saved", null, LocalDateTime.now()),
                    () -> { throw new CredentialsExpiredException("revoked replay lease"); });
            until(() -> manager.managed() == 0);
            verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class)); verify(emitter).complete();
        } finally { close(manager); }
    }

    @Test void shouldRejectUnboundedConfigurationAndExposeScopedEnvironmentOverrides() throws Exception {
        for (int workers : List.of(0, 33)) assertThatThrownBy(() -> new AssistantStreamTransport(workers, 1)).isInstanceOf(IllegalArgumentException.class);
        for (int capacity : List.of(-1, 1025)) assertThatThrownBy(() -> new AssistantStreamTransport(1, capacity)).isInstanceOf(IllegalArgumentException.class);
        var source = new org.springframework.boot.env.YamlPropertySourceLoader().load("application",
                new org.springframework.core.io.ClassPathResource("application.yml")).get(0);
        assertThat(source.getProperty("opspilot.assistant.stream-writers")).isEqualTo("${ASSISTANT_STREAM_WRITERS:4}");
        assertThat(source.getProperty("opspilot.assistant.stream-queue-capacity")).isEqualTo("${ASSISTANT_STREAM_QUEUE_CAPACITY:16}");
    }

    static void until(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(done.getAsBoolean()).isTrue();
    }
    static void close(AssistantStreamTransport manager) throws Exception { manager.shutdown(); until(() -> manager.managed() == 0); }
}
