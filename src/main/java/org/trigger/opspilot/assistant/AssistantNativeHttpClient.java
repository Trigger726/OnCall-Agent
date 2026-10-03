package org.trigger.opspilot.assistant;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** JDK can complete a cancelled exchange with CompletionException(CancellationException).
 * Settle the subscriber-facing future as cancelled before cancelling the physical exchange.
 * Ordinary provider failures are not filtered, reclassified, or retried. */
final class AssistantNativeHttpClient extends HttpClient {
    private final HttpClient delegate;

    AssistantNativeHttpClient(HttpClient delegate) { this.delegate = delegate; }

    static <T> CompletableFuture<T> cancellationFirst(CompletableFuture<T> exchange) {
        var result = new CompletableFuture<T>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled) exchange.cancel(mayInterruptIfRunning);
                return cancelled;
            }
        };
        exchange.whenComplete((value, failure) -> {
            if (failure == null) result.complete(value);
            else result.completeExceptionally(failure);
        });
        return result;
    }

    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return cancellationFirst(delegate.sendAsync(request, handler));
    }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> pushHandler) {
        return cancellationFirst(delegate.sendAsync(request, handler, pushHandler));
    }
    @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        return delegate.send(request, handler);
    }
    @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
    @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
    @Override public Redirect followRedirects() { return delegate.followRedirects(); }
    @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
    @Override public SSLContext sslContext() { return delegate.sslContext(); }
    @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
    @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
    @Override public Version version() { return delegate.version(); }
    @Override public Optional<Executor> executor() { return delegate.executor(); }
}
