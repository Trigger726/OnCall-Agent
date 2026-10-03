package org.trigger.opspilot.assistant;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AssistantNativeHttpClientTest {
    @Test void cancellationSettlesSubscriberBeforeJdkWrappedCancellationArrives() {
        var calls = new AtomicInteger();
        var exchange = new CompletableFuture<String>() {
            @Override public boolean cancel(boolean interrupt) {
                calls.incrementAndGet();
                return completeExceptionally(new CompletionException(new CancellationException("Request cancelled")));
            }
        };
        var result = AssistantNativeHttpClient.cancellationFirst(exchange);
        var observed = new AtomicReference<Throwable>();
        result.whenComplete((value, failure) -> observed.set(failure));
        var subscription = reactor.core.publisher.Mono.fromCompletionStage(result).subscribe();
        subscription.dispose();
        assertThat(result.isCancelled()).isTrue(); assertThat(calls).hasValue(1);
        assertThat(observed.get()).isExactlyInstanceOf(CancellationException.class);
        assertThat(exchange.isCompletedExceptionally()).isTrue();
    }

    @Test void ordinaryProviderFailureIsPropagatedWithoutBeingReclassifiedOrIgnored() {
        var exchange = new CompletableFuture<String>();
        var result = AssistantNativeHttpClient.cancellationFirst(exchange);
        var observed = new AtomicReference<Throwable>(); result.whenComplete((value, failure) -> observed.set(failure));
        var failure = new CompletionException(new IOException("controlled provider failure"));
        exchange.completeExceptionally(failure);
        assertThat(observed.get()).isSameAs(failure); assertThat(result.isCancelled()).isFalse();
    }

    @Test void successIsExactAndCancellingOneExchangeCannotCancelAnother() {
        var first = new CompletableFuture<String>(); var second = new CompletableFuture<String>();
        var one = AssistantNativeHttpClient.cancellationFirst(first); var two = AssistantNativeHttpClient.cancellationFirst(second);
        one.cancel(true); assertThat(first.isCancelled()).isTrue(); assertThat(second.isDone()).isFalse();
        second.complete("  中文🙂\n"); assertThat(two.join()).isEqualTo("  中文🙂\n");
        assertThat(two.cancel(false)).isFalse();
    }

    @Test void lateSuccessCannotReplaceCancellationEvenWhenUnderlyingFutureCannotBeCancelled() {
        var exchange = new CompletableFuture<String>() { @Override public boolean cancel(boolean interrupt) { return false; } };
        var result = AssistantNativeHttpClient.cancellationFirst(exchange);
        result.cancel(false); exchange.complete("late"); assertThat(result.isCancelled()).isTrue();
    }

    @Test void clientDelegatesBothAsyncOverloadsSyncAndConfiguration() throws Exception {
        var original = mock(HttpClient.class); var wrapped = new AssistantNativeHttpClient(original);
        var request = HttpRequest.newBuilder(URI.create("http://localhost/owned")).build();
        var handler = HttpResponse.BodyHandlers.ofString();
        HttpResponse.PushPromiseHandler<String> push = (initiating, promised, acceptor) -> {};
        var first = new CompletableFuture<HttpResponse<String>>(); var second = new CompletableFuture<HttpResponse<String>>();
        when(original.sendAsync(request, handler)).thenReturn(first);
        when(original.sendAsync(request, handler, push)).thenReturn(second);
        wrapped.sendAsync(request, handler).cancel(true); assertThat(first.isCancelled()).isTrue();
        wrapped.sendAsync(request, handler, push).cancel(true); assertThat(second.isCancelled()).isTrue();
        var response = mock(HttpResponse.class); when(original.send(request, handler)).thenReturn(response);
        assertThat(wrapped.send(request, handler)).isSameAs(response);
        wrapped.sslContext(); wrapped.sslParameters(); wrapped.connectTimeout(); wrapped.proxy(); wrapped.authenticator();
        wrapped.cookieHandler(); wrapped.version(); wrapped.followRedirects(); wrapped.executor();
        verify(original).sslContext(); verify(original).sslParameters(); verify(original).connectTimeout();
        verify(original).proxy(); verify(original).authenticator(); verify(original).cookieHandler();
        verify(original).version(); verify(original).followRedirects(); verify(original).executor();
    }
}
