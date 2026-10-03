package org.trigger.opspilot.assistant;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantNativeStreamHttpTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void shouldRequestNativeIncrementalProtocolRatherThanACompleteJsonAnswer() throws Exception {
        var sseHeader = new AtomicReference<String>();
        var request = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/services/aigc/text-generation/generation", exchange -> {
            sseHeader.set(exchange.getRequestHeaders().getFirst("X-DashScope-SSE"));
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write(frame("完整", "null")); body.flush();
                body.write(frame("答案", "stop")); body.flush();
            }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var api = DashScopeApi.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .apiKey("cp74-controlled-not-a-real-key").build();
            var model = DashScopeChatModel.builder().dashScopeApi(api).build();
            var ai = new AssistantAiService(ChatClient.builder(model));
            assertThat(ai.streamAnswer("已有事实", "", "只读建议").collectList().block(Duration.ofSeconds(5)))
                    .containsExactly("完整", "答案");
            assertThat(sseHeader.get()).as("Native streaming must be requested at the actual HTTP boundary").isEqualTo("enable");
            assertThat(json.readTree(request.get()).path("parameters").path("incremental_output").asBoolean()).isTrue();
        } finally { server.stop(0); }
    }

    @Test void shouldDeliverFirstRealHttpDeltaBeforeProviderCanFinish() throws Exception {
        var received = new CountDownLatch(1); var release = new CountDownLatch(1);
        var complete = new CountDownLatch(1); var serverFinished = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>(); var calls = new AtomicInteger();
        var chunks = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newSingleThreadExecutor(); server.setExecutor(executor);
        server.createContext("/api/v1/services/aigc/text-generation/generation", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write(frame("第一段中文🙂", "null")); body.flush();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Controlled provider gate expired");
                body.write(frame("第二段", "stop")); body.flush();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); serverFinished.countDown(); }
        });
        server.start();
        var api = DashScopeApi.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("cp74-controlled-not-a-real-key").build();
        var ai = new AssistantAiService(ChatClient.builder(DashScopeChatModel.builder().dashScopeApi(api).build()));
        var subscription = ai.streamAnswer("已有事实", "", "只读建议").subscribe(text -> {
            chunks.add(text); received.countDown();
        }, error -> { failure.set(error); complete.countDown(); }, complete::countDown);
        try {
            assertThat(received.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(chunks).containsExactly("第一段中文🙂");
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(complete.getCount()).isEqualTo(1);
            assertThat(serverFinished.getCount()).isEqualTo(1);
            release.countDown();
            assertThat(complete.await(3, TimeUnit.SECONDS)).isTrue(); assertThat(failure.get()).isNull();
            assertThat(chunks).containsExactly("第一段中文🙂", "第二段"); assertThat(calls).hasValue(1);
        } finally {
            release.countDown(); subscription.dispose(); server.stop(0); executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void shouldRejectRealHttpEofWithoutStopRatherThanAcceptingPartialAnswer() throws Exception {
        try (var fixture = new HttpFixture(List.of(frame("未完成", "null")))) {
            var emitted = new java.util.ArrayList<String>();
            assertThatThrownBy(() -> fixture.ai.streamAnswer("", "", "只读建议").doOnNext(emitted::add)
                    .collectList().block(Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("without a complete answer");
            assertThat(emitted).containsExactly("未完成"); assertThat(fixture.calls).hasValue(1);
        }
    }

    @Test void shouldRejectRealHttpLengthTerminationWithoutRetryOrRuleFallback() throws Exception {
        try (var fixture = new HttpFixture(List.of(frame("部分", "null"), frame("截断", "length")))) {
            var emitted = new java.util.ArrayList<String>();
            assertThatThrownBy(() -> fixture.ai.streamAnswer("", "", "只读建议").doOnNext(emitted::add)
                    .collectList().block(Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("did not complete normally");
            assertThat(emitted).containsExactly("部分"); assertThat(fixture.calls).hasValue(1);
        }
    }

    private static byte[] frame(String content, String reason) {
        return ("data:{\"output\":{\"choices\":[{\"finish_reason\":\"" + reason
                + "\",\"message\":{\"role\":\"assistant\",\"content\":\"" + content
                + "\"}}]},\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2},"
                + "\"request_id\":\"cp74-controlled\"}\n\n").getBytes(StandardCharsets.UTF_8);
    }

    private static final class HttpFixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final AssistantAiService ai;
        HttpFixture(List<byte[]> frames) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/services/aigc/text-generation/generation", exchange -> {
                calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
                try (var body = exchange.getResponseBody()) { for (var frame : frames) { body.write(frame); body.flush(); } }
                finally { exchange.close(); }
            });
            server.start();
            var api = DashScopeApi.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .apiKey("cp74-controlled-not-a-real-key").build();
            ai = new AssistantAiService(ChatClient.builder(DashScopeChatModel.builder().dashScopeApi(api).build()));
        }
        @Override public void close() { server.stop(0); }
    }
}
