package org.trigger.opspilot.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantNativeTransportTest {
    @Test void originalConnectorIsPreservedOutsideNativeAssistantScope() {
        var original = new AtomicInteger(); var nativeCalls = new AtomicInteger();
        var connector = AssistantNativeTransport.scopedConnector((method, uri, callback) -> {
            original.incrementAndGet(); return reactor.core.publisher.Mono.empty();
        }, (method, uri, callback) -> { nativeCalls.incrementAndGet(); return reactor.core.publisher.Mono.empty(); });
        connector.connect(org.springframework.http.HttpMethod.POST, URI.create("http://localhost/owned"), ignored -> reactor.core.publisher.Mono.empty()).block();
        assertThat(original).hasValue(1); assertThat(nativeCalls).hasValue(0);
    }

    @Test void onlyNativeContextSelectsDedicatedConnector() {
        var original = new AtomicInteger(); var nativeCalls = new AtomicInteger();
        var connector = AssistantNativeTransport.scopedConnector((method, uri, callback) -> {
            original.incrementAndGet(); return reactor.core.publisher.Mono.empty();
        }, (method, uri, callback) -> { nativeCalls.incrementAndGet(); return reactor.core.publisher.Mono.empty(); });
        AssistantNativeTransport.cancellable(connector.connect(org.springframework.http.HttpMethod.POST,
                URI.create("http://localhost/owned"), ignored -> reactor.core.publisher.Mono.empty()).flux()).blockLast();
        assertThat(original).hasValue(0); assertThat(nativeCalls).hasValue(1);
    }

    @Test void cancellationClosesBodyEvenWhenIntermediatePublisherRetainsItsUpstream() {
        var cancelled = new AtomicInteger();
        var client = client(cancelled);
        var retained = client.post().header("X-DashScope-SSE", "enable").retrieve().bodyToFlux(String.class)
                .publish().autoConnect(1);
        assertThat(AssistantNativeTransport.cancellable(retained).take(1).blockLast(Duration.ofSeconds(3))).isEqualTo("first");
        assertThat(cancelled).hasValue(1);
    }

    @Test void validationFailureAlsoClosesRetainedBodyWithoutRetry() {
        var cancelled = new AtomicInteger(); var calls = new AtomicInteger();
        var retained = client(cancelled).post().header("X-DashScope-SSE", "enable").retrieve().bodyToFlux(String.class)
                .doOnSubscribe(subscription -> calls.incrementAndGet()).publish().autoConnect(1);
        assertThatThrownBy(() -> AssistantNativeTransport.cancellable(retained.<String>map(chunk -> {
            throw new IllegalStateException("validation failure");
        })).blockLast(Duration.ofSeconds(3))).hasMessage("validation failure");
        assertThat(cancelled).hasValue(1); assertThat(calls).hasValue(1);
    }

    @Test void eachColdSubscriptionHasItsOwnCancellationSignal() {
        var cancelled = new AtomicInteger(); var client = client(cancelled);
        var stream = AssistantNativeTransport.cancellable(client.post().header("X-DashScope-SSE", "enable").retrieve().bodyToFlux(String.class));
        assertThat(stream.take(1).blockLast(Duration.ofSeconds(3))).isEqualTo("first");
        assertThat(stream.take(1).blockLast(Duration.ofSeconds(3))).isEqualTo("first");
        assertThat(cancelled).hasValue(2);
    }

    @Test void requestsWithoutAssistantScopeKeepTheirResponseUnmodified() {
        var response = ClientResponse.create(HttpStatus.OK).body("plain").build();
        var request = ClientRequest.create(org.springframework.http.HttpMethod.POST, URI.create("http://localhost/owned"))
                .header("X-DashScope-SSE", "enable").build();
        assertThat(AssistantNativeTransport.cancellationFilter().filter(request, ignored -> reactor.core.publisher.Mono.just(response))
                .block(Duration.ofSeconds(3))).isSameAs(response);
    }

    @Test void scopedNonNativeRequestsKeepTheirResponseUnmodified() {
        var response = ClientResponse.create(HttpStatus.OK).body("plain").build();
        var request = ClientRequest.create(org.springframework.http.HttpMethod.POST, URI.create("http://localhost/owned")).build();
        assertThat(AssistantNativeTransport.cancellable(AssistantNativeTransport.cancellationFilter()
                .filter(request, ignored -> reactor.core.publisher.Mono.just(response)).flux()).blockLast(Duration.ofSeconds(3))).isSameAs(response);
    }

    @Test void cancellationBeforeHeadersClosesRetainedPendingExchange() {
        var cancelled = new AtomicInteger();
        var client = WebClient.builder().filter(AssistantNativeTransport.cancellationFilter()).exchangeFunction(request ->
                reactor.core.publisher.Mono.<ClientResponse>never().doOnCancel(cancelled::incrementAndGet)).build();
        var retained = client.post().header("X-DashScope-SSE", "enable").retrieve().bodyToFlux(String.class).publish().autoConnect(1);
        var subscription = AssistantNativeTransport.cancellable(retained).subscribe(); subscription.dispose();
        assertThat(cancelled).hasValue(1);
    }

    private static WebClient client(AtomicInteger cancelled) {
        return WebClient.builder().filter(AssistantNativeTransport.cancellationFilter()).exchangeFunction(request -> {
            org.springframework.core.io.buffer.DataBuffer first = DefaultDataBufferFactory.sharedInstance
                    .wrap("data:first\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return reactor.core.publisher.Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "text/event-stream")
                    .body(Flux.concat(Flux.just(first), Flux.never()).doOnCancel(cancelled::incrementAndGet)).build());
        }).build();
    }
}
