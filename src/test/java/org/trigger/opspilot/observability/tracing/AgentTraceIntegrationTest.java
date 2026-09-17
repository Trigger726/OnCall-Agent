package org.trigger.opspilot.observability.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.trigger.opspilot.alert.AlertService;
import org.trigger.opspilot.alert.AlertController;
import org.trigger.opspilot.investigation.AgentExecutionManager;
import org.trigger.opspilot.investigation.AgentRunEventService;
import org.trigger.opspilot.investigation.InvestigationService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-tracing-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false",
        "opspilot.agent.recovery.enabled=false",
        "management.tracing.enabled=false"
})
@Import(AgentTraceIntegrationTest.TracerConfig.class)
class AgentTraceIntegrationTest {
    @Autowired private InvestigationService investigationService;
    @Autowired private AgentExecutionManager executionManager;
    @Autowired private AlertController alertController;
    @Autowired private SimpleTracer tracer;

    @BeforeEach
    void clearSpans() {
        tracer.getSpans().clear();
    }

    @Test
    void shouldAttachCreatedAlertAndIncidentIdentifiersToRequestTrace() {
        Span root = tracer.nextSpan().name("http.post.alert-intake").start();
        AlertService.IntakeResult result;
        try (Tracer.SpanInScope ignored = tracer.withSpan(root)) {
            result = alertController.intake(new AlertController.IntakeRequest(
                    "trace-test", "trace-alert-" + UUID.randomUUID(), "APP-AUTH", "P3", "FIRING",
                    "认证服务错误率升高", "controlled trace test", Map.of("cluster", "test"),
                    LocalDateTime.now())).data();
        } finally {
            root.end();
        }

        SimpleSpan intake = spans("opspilot.alert.intake").get(0);
        assertThat(intake.getTraceId()).isEqualTo(root.context().traceId());
        assertThat(intake.getParentId()).isEqualTo(root.context().spanId());
        assertThat(intake.getTags()).containsEntry("opspilot.alert.action", "CREATED")
                .containsEntry("opspilot.alert.id", String.valueOf(result.alertId()))
                .containsEntry("opspilot.incident.id", String.valueOf(result.incidentId()));
    }

    @Test
    void shouldKeepHttpParentAcrossExecutorAndCreateToolAndProviderChildren() throws Exception {
        InvestigationService.RunActor actor = new InvestigationService.RunActor(1L, "127.0.0.1");
        InvestigationService.PreparedRun prepared = investigationService.prepare(
                1, "TRACE_INTEGRATION_TEST", actor, "trace-" + UUID.randomUUID(), Duration.ofSeconds(30));
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Span root = tracer.nextSpan().name("http.post.investigation").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(root)) {
            executionManager.submit(prepared.runId(), prepared.deadlineAt(), () -> {
                try {
                    investigationService.execute(prepared, actor, AgentRunEventService.EventSink.NOOP);
                } catch (Throwable exception) {
                    failure.set(exception);
                } finally {
                    finished.countDown();
                }
            }, () -> investigationService.requestTimeout(prepared.runId()));
        } finally {
            root.end();
        }

        assertThat(finished.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        SimpleSpan run = spans("opspilot.agent.run").get(0);
        List<SimpleSpan> tools = spans("opspilot.agent.tool");
        List<SimpleSpan> providers = spans("opspilot.provider.query");
        assertThat(run.getTraceId()).isEqualTo(root.context().traceId());
        assertThat(run.getParentId()).isEqualTo(root.context().spanId());
        assertThat(run.getTags()).containsEntry("opspilot.agent.run.id", String.valueOf(prepared.runId()))
                .containsEntry("opspilot.agent.status", "COMPLETED");
        assertThat(tools).hasSize(6).allSatisfy(tool -> {
            assertThat(tool.getTraceId()).isEqualTo(run.getTraceId());
            assertThat(tool.getParentId()).isEqualTo(run.getSpanId());
        });
        assertThat(providers).hasSize(2).allSatisfy(provider -> {
            assertThat(provider.getTraceId()).isEqualTo(run.getTraceId());
            assertThat(tools).anySatisfy(tool -> assertThat(provider.getParentId()).isEqualTo(tool.getSpanId()));
        });
        assertThat(providers).extracting(span -> span.getTags().get("opspilot.provider.kind"))
                .containsExactlyInAnyOrder("metrics", "logs");
    }

    private List<SimpleSpan> spans(String name) {
        return tracer.getSpans().stream().filter(span -> name.equals(span.getName())).toList();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TracerConfig {
        @Bean
        @Primary
        SimpleTracer simpleTracer() {
            return new SimpleTracer();
        }
    }
}
