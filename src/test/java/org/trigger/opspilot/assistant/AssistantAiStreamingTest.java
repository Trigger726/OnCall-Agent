package org.trigger.opspilot.assistant;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AssistantAiStreamingTest {
    private static final Duration LIMIT = Duration.ofSeconds(3);

    @Test void shouldPreserveExactIncrementalWhitespaceAndUnicodeWithEmptyStopFrame() {
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("  证据🙂", null),
                response("\n下一步  ", "null"), response("", "stop"), new ChatResponse(List.of())));
        assertThat(ai(model).streamAnswer("facts", "history", "question").collectList().block(LIMIT))
                .containsExactly("  证据🙂", "\n下一步  ");
        verify(model, never()).call(any(Prompt.class));
    }

    @Test void shouldRejectEmptyOrWhitespaceOnlyAnswerEvenWithStop() {
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(" \n ", "stop")));
        assertThatThrownBy(() -> ai(model).streamAnswer("", "", "question").collectList().block(LIMIT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("without a complete answer");
    }

    @Test void shouldRejectEmptyStreamAndNonterminalEof() {
        for (var source : List.of(Flux.<ChatResponse>empty(), Flux.just(response("partial", null)))) {
            var model = mock(ChatModel.class); when(model.stream(any(Prompt.class))).thenReturn(source);
            assertThatThrownBy(() -> ai(model).streamAnswer("", "", "question").collectList().block(LIMIT))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("without a complete answer");
        }
    }

    @Test void shouldRejectUnknownFilteredOrToolTerminalWithoutEmittingItsContent() {
        for (var reason : List.of("length", "tool_calls", "content_filter", "unexpected")) {
            var model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("must not emit", reason)));
            var emitted = new ArrayList<String>();
            assertThatThrownBy(() -> ai(model).streamAnswer("", "", "question").doOnNext(emitted::add).collectList().block(LIMIT))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("did not complete normally");
            assertThat(emitted).isEmpty();
        }
    }

    @Test void shouldNotMergeMultipleAlternativeGenerations() {
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(
                response("first", "stop").getResult(), response("second", "stop").getResult()))));
        assertThatThrownBy(() -> ai(model).streamAnswer("", "", "question").collectList().block(LIMIT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid");
    }

    @Test void shouldRejectActualToolCallPayloadAndDisableSdkToolExecutionInPrompt() {
        var model = mock(ChatModel.class);
        var tool = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("tool-1", "function", "controlled_tool", "{}"))).build();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            var prompt = invocation.<Prompt>getArgument(0);
            assertThat(prompt.getOptions()).isInstanceOf(DashScopeChatOptions.class);
            var options = (DashScopeChatOptions) prompt.getOptions();
            assertThat(options.getInternalToolExecutionEnabled()).isFalse();
            assertThat(options.getEnableThinking()).isFalse();
            assertThat(options.getIncrementalOutput()).isTrue();
            return Flux.just(new ChatResponse(List.of(new Generation(tool))));
        });
        assertThatThrownBy(() -> ai(model).streamAnswer("", "", "question").collectList().block(LIMIT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid");
    }

    @Test void shouldRejectMoreContentOrDuplicateTerminalAfterStop() {
        for (var after : List.of(response("late", null), response("", "stop"))) {
            var model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("first", "stop"), after));
            var emitted = new ArrayList<String>();
            assertThatThrownBy(() -> ai(model).streamAnswer("", "", "question").doOnNext(emitted::add).collectList().block(LIMIT))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("after completion");
            assertThat(emitted).containsExactly("first");
        }
    }

    @Test void shouldPropagateLateProviderFailureWithoutRetryOrFallbackEvenAfterStop() {
        var calls = new AtomicInteger(); var failure = new IllegalStateException("controlled transport failure");
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.defer(() -> {
            calls.incrementAndGet(); return Flux.just(response("partial", "stop")).concatWith(Flux.error(failure));
        }));
        var emitted = new ArrayList<String>();
        assertThatThrownBy(() -> ai(model).streamAnswer("", "", "question").doOnNext(emitted::add).collectList().block(LIMIT))
                .isSameAs(failure);
        assertThat(emitted).containsExactly("partial"); assertThat(calls).hasValue(1); verify(model, never()).call(any(Prompt.class));
    }

    @Test void shouldAcceptExactCharacterBoundaryAndRejectOverflowBeforeDelivery() {
        var model = mock(ChatModel.class); String exact = "x".repeat(AssistantAiService.MAX_STREAM_CHARACTERS);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(exact, "stop")));
        assertThat(ai(model).streamAnswer("", "", "question").collectList().block(LIMIT)).containsExactly(exact);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(exact, null), response("overflow", "stop")));
        var emitted = new ArrayList<String>();
        assertThatThrownBy(() -> ai(model).streamAnswer("", "", "question").doOnNext(emitted::add).collectList().block(LIMIT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeds stream limit");
        assertThat(emitted).containsExactly(exact);
    }

    @Test void shouldBoundResponseCountIncludingEmptyFramesAndCancelSource() {
        var cancelled = new AtomicInteger(); var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.range(0, AssistantAiService.MAX_STREAM_RESPONSES + 1)
                .map(i -> response("", null)).doOnCancel(cancelled::incrementAndGet));
        assertThatThrownBy(() -> ai(model).streamAnswer("", "", "question").collectList().block(LIMIT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid");
        assertThat(cancelled).hasValue(1);
    }

    @Test void shouldAcceptExactResponseCountBoundary() {
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.range(0, AssistantAiService.MAX_STREAM_RESPONSES)
                .map(i -> response(i == 0 ? "one" : "", i == AssistantAiService.MAX_STREAM_RESPONSES - 1 ? "STOP" : null)));
        assertThat(ai(model).streamAnswer("", "", "question").collectList().block(LIMIT)).containsExactly("one");
    }

    @Test void shouldKeepValidationStateIsolatedForEachColdSubscription() {
        var model = mock(ChatModel.class); when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("one", "stop")));
        var stream = ai(model).streamAnswer("", "", "question");
        assertThat(stream.collectList().block(LIMIT)).containsExactly("one");
        assertThat(stream.collectList().block(LIMIT)).containsExactly("one");
        verify(model, times(2)).stream(any(Prompt.class));
    }

    @Test void shouldCancelIdleUpstreamWithoutClaimingNormalCompletion() {
        var cancelled = new AtomicInteger(); var completed = new AtomicInteger(); var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(response("partial", null)),
                Flux.<ChatResponse>never()).doOnCancel(cancelled::incrementAndGet));
        var stream = ai(model).streamAnswer("", "", "question").doOnComplete(completed::incrementAndGet);
        var subscription = stream.subscribe(); subscription.dispose();
        assertThat(cancelled).hasValue(1); assertThat(completed).hasValue(0); verify(model, never()).call(any(Prompt.class));
    }

    private static AssistantAiService ai(ChatModel model) { return new AssistantAiService(ChatClient.builder(model)); }
    private static ChatResponse response(String text, String reason) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(reason).build())));
    }
}
