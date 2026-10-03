package org.trigger.opspilot.assistant;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class AssistantStreamReaderTest {
    @Test void preservesExactFragmentsAndRequestsOnlyOneAtATime() {
        var requested = new ArrayList<Long>(); var seen = new ArrayList<String>();
        String answer = AssistantStreamReader.read(Flux.just("  中文🙂\n", "下一步  ").doOnRequest(requested::add), () -> { }, seen::add);
        assertThat(answer).isEqualTo("  中文🙂\n下一步  ");
        assertThat(seen).containsExactly("  中文🙂\n", "下一步  ");
        assertThat(requested).isNotEmpty().allMatch(amount -> amount == 1);
    }

    @Test void idleProviderStillChecksStopAndCancelsSubscription() {
        var cancelled = new AtomicBoolean(); var checks = new AtomicInteger();
        var stop = new IllegalStateException("controlled stop");
        assertThatThrownBy(() -> AssistantStreamReader.read(Flux.<String>never().doOnCancel(() -> cancelled.set(true)),
                () -> { if (checks.incrementAndGet() == 4) throw stop; }, chunk -> fail("No tokens expected"))).isSameAs(stop);
        assertThat(cancelled).isTrue(); assertThat(checks).hasValue(4);
    }

    @Test void stoppedBeforeStartNeverSubscribes() {
        var subscribed = new AtomicBoolean();
        assertThatThrownBy(() -> AssistantStreamReader.read(Flux.defer(() -> { subscribed.set(true); return Flux.just("late"); }),
                () -> { throw new IllegalStateException("stopped"); }, chunk -> { })).hasMessage("stopped");
        assertThat(subscribed).isFalse();
    }

    @Test void previewCallbackFailureCancelsTheProvider() {
        var cancelled = new AtomicBoolean(); var failure = new IllegalStateException("preview failed");
        assertThatThrownBy(() -> AssistantStreamReader.read(Flux.concat(Flux.just("first"), Flux.never()).doOnCancel(() -> cancelled.set(true)),
                () -> { }, chunk -> { throw failure; })).isSameAs(failure);
        assertThat(cancelled).isTrue();
    }

    @Test void upstreamFailureCannotReturnAnAnswer() {
        var failure = new IllegalStateException("provider truncated");
        assertThatThrownBy(() -> AssistantStreamReader.read(Flux.concat(Flux.just("preview"), Flux.error(failure)),
                () -> { }, chunk -> { })).isSameAs(failure);
    }

    @Test void emptyOrWhitespaceOnlyCannotCommit() {
        for (Flux<String> source : java.util.List.of(Flux.<String>empty(), Flux.just(" \n"))) {
            assertThatThrownBy(() -> AssistantStreamReader.read(source, () -> { }, chunk -> { })).hasMessageContaining("empty");
        }
    }

    @Test void sizeLimitIsCheckedBeforePublishingAnOversizedFragment() {
        var seen = new ArrayList<String>();
        assertThatThrownBy(() -> AssistantStreamReader.read(Flux.just("x".repeat(100_001)), () -> { }, seen::add))
                .hasMessageContaining("bounded size");
        assertThat(seen).isEmpty();
    }
}
