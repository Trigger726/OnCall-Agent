package org.trigger.opspilot.assistant;

import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Fence the actual response body even when the provider SDK retains an open inner window on cancellation. */
@Configuration(proxyBeanMethods = false)
class AssistantNativeTransport {
    private static final Object CANCELLATION = new Object();

    @Bean @Order(Ordered.LOWEST_PRECEDENCE)
    WebClientCustomizer assistantNativeTransportCancellation(ClientHttpConnector original) {
        // A single dedicated JDK client for native assistant streams. All other calls keep Boot's connector.
        var nativeConnector = new JdkClientHttpConnector(new AssistantNativeHttpClient(java.net.http.HttpClient.newHttpClient()));
        return builder -> builder.clientConnector(scopedConnector(original, nativeConnector)).filter(cancellationFilter());
    }

    static ClientHttpConnector scopedConnector(ClientHttpConnector original, ClientHttpConnector nativeConnector) {
        return (method, uri, callback) -> Mono.deferContextual(context ->
                (context.hasKey(CANCELLATION) ? nativeConnector : original).connect(method, uri, callback));
    }

    static ExchangeFilterFunction cancellationFilter() {
        return (request, next) -> Mono.deferContextual(context -> {
            if (!context.hasKey(CANCELLATION) || !"enable".equals(request.headers().getFirst("X-DashScope-SSE"))) {
                return next.exchange(request);
            }
            Mono<Void> stopped = context.get(CANCELLATION);
            return next.exchange(request).takeUntilOther(stopped)
                    .map(response -> response.mutate().body(body -> body.takeUntilOther(stopped)
                            .doOnDiscard(DataBuffer.class, DataBufferUtils::release)).build());
        });
    }

    static <T> Flux<T> cancellable(Flux<T> source) {
        return Flux.defer(() -> {
            var stopped = Sinks.<Void>empty();
            return source.contextWrite(context -> context.put(CANCELLATION, stopped.asMono()))
                    .doFinally(signal -> stopped.tryEmitEmpty());
        });
    }
}
