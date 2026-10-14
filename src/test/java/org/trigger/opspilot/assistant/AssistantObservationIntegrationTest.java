package org.trigger.opspilot.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.incident.IncidentService;
import org.trigger.opspilot.investigation.AgentRunQueryService;
import org.trigger.opspilot.observability.LogRedactor;
import org.trigger.opspilot.observability.ObservationQueryContextFactory;
import org.trigger.opspilot.observability.logs.LogsProvider;
import org.trigger.opspilot.observability.logs.LogsProviderRouter;
import org.trigger.opspilot.observability.metrics.MetricsProvider;
import org.trigger.opspilot.observability.metrics.MetricsProviderRouter;
import org.trigger.opspilot.observability.tracing.OpsPilotTracing;
import org.trigger.opspilot.runbook.RunbookService;
import org.trigger.opspilot.security.SessionAuthorization;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:assistant-observations;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "management.server.port=0", "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false",
        "opspilot.runbook.semantic.enabled=false", "opspilot.agent.recovery.enabled=false",
        "opspilot.oncall.rotation.enabled=false", "opspilot.oncall.escalation.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AssistantObservationIntegrationTest {
    @Autowired AssistantService assistant;
    @Autowired AssistantObservationService realObservations;
    @Autowired IncidentService incidents;
    @Autowired ObservationQueryContextFactory contexts;
    @Autowired JdbcClient jdbc;
    @Autowired SessionAuthorization authorization;
    @Autowired RunbookService runbooks;
    @Autowired ObjectMapper json;
    @Autowired AgentRunQueryService agentRuns;
    @Autowired TransactionTemplate transactions;
    @Autowired AssistantRequestStore requests;
    MetricsProvider metrics;
    LogsProvider logs;
    long owner, session;
    final OpsPilotTracing tracing = OpsPilotTracing.using(Tracer.NOOP);

    @BeforeEach void setup() {
        String name = "obs_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("""
                INSERT INTO sys_user(username,password_hash,display_name,role_code,status)
                SELECT :name,password_hash,'Observation acceptance','ON_CALL','ACTIVE' FROM sys_user WHERE username='admin'
                """).param("name", name).update();
        owner = jdbc.sql("SELECT id FROM sys_user WHERE username=:name").param("name", name).query(Long.class).single();
        session = assistant.createSession(owner, 1L, null).session().id();
        metrics = mock(MetricsProvider.class); logs = mock(LogsProvider.class);
        when(metrics.available()).thenReturn(true); when(metrics.id()).thenReturn("test-metrics");
        when(logs.available()).thenReturn(true); when(logs.id()).thenReturn("test-logs");
        when(metrics.query(any())).thenAnswer(call -> {
            var q = call.<MetricsProvider.MetricsQuery>getArgument(0);
            return new MetricsProvider.MetricsResult("test-metrics", "fixed-resource-query", null,
                    List.of(new MetricsProvider.MetricSample("1", q.resourceCode(), "cpu_usage", "83", "percent",
                            Map.of("token", "OBS_SECRET", "email", "user@example.com"), q.end().minusMinutes(1))), List.of());
        });
        when(logs.query(any())).thenAnswer(call -> {
            var q = call.<LogsProvider.LogsQuery>getArgument(0);
            return new LogsProvider.LogsResult("test-logs", "fixed-resource-query", null,
                    List.of(new LogsProvider.LogEntry("1", q.resourceCode(), "ERROR", "Service", "timeout token=OBS_SECRET",
                            "trace-qa", Map.of("password", "OBS_SECRET"), q.end().minusMinutes(1))), 0, List.of());
        });
    }

    @Test void actualLocalProvidersShouldReturnSeededFaultWindowAndDurableSanitizedSnapshots() throws Exception {
        var answer = assistant.sendMessage(session, owner, "查询故障窗口的指标和日志");
        assertThat(answer.content()).contains("local-metrics", "local-logs", "Incident 故障窗口（不是实时）", "redis_pool_pending=84", "token=***")
                .doesNotContain("fake-demo-token-123");
        var refs = json.readValue(answer.evidenceJson(), AssistantService.EvidenceRef[].class);
        assertThat(List.of(refs)).anySatisfy(ref -> {
            assertThat(ref.type()).isEqualTo("METRIC"); assertThat(ref.snapshot().provider()).isEqualTo("local-metrics");
            assertThat(ref.snapshot().observedAt()).isBetween(ref.snapshot().windowStart(), ref.snapshot().windowEnd());
        });
        assertThat(assistant.getSession(session, owner).messages().get(1)).isEqualTo(answer);
    }

    @Test void latestShouldBeExactlyRollingThirtyMinutesNotIncidentSeedData() {
        var snapshot = observations().collect(1L, "最新指标和日志", authorization.capture(owner), () -> {});
        assertThat(snapshot.channels()).hasSize(2).allSatisfy(c -> {
            assertThat(c.window()).isEqualTo("RECENT_30_MINUTES");
            assertThat(java.time.Duration.between(c.start(), c.end()).toMinutes()).isEqualTo(30);
            assertThat(c.end()).isAfter(LocalDateTime.now().minusSeconds(10));
        });
        assertThat(snapshot.summary()).contains("cpu_usage=83", "token=***").doesNotContain("OBS_SECRET", "user@example.com");
        verify(logs).query(argThat(q -> q.limit() == 20 && q.incidentId() == 1L));
    }

    @Test void defaultWindowShouldReuseIncidentResourceAndIgnoreQuestionResourceOrQueryExpression() {
        var context = contexts.from(incidents.get(1));
        var snapshot = observations().collect(1L, "指标 日志 resource=unknown query={job=other}", authorization.capture(owner), () -> {});
        assertThat(snapshot.channels()).allSatisfy(c -> {
            assertThat(c.window()).isEqualTo("INCIDENT_WINDOW"); assertThat(c.start()).isEqualTo(context.start());
            assertThat(c.end()).isEqualTo(context.end()); assertThat(c.resourceCode()).isEqualTo(context.resourceCode());
        });
        verify(metrics).query(argThat(q -> q.resourceId() == context.resourceId() && q.resourceCode().equals(context.resourceCode())));
    }

    @Test void absentIncidentGenericQuestionAuditorAndUnsupportedRangesShouldNotQueryProviders() {
        var service = observations(); var lease = authorization.capture(owner);
        assertThat(service.collect(null, "最新指标和日志", lease, () -> {}).status()).isEqualTo("INCIDENT_REQUIRED");
        assertThat(service.collect(1L, "Runbook Redis pending", lease, () -> {}).status()).isEqualTo("NOT_REQUESTED");
        for (String question : List.of("过去24小时日志", "最近1小时指标", "昨天日志", "2026-10-10指标")) {
            assertThat(service.collect(1L, question, lease, () -> {}).status()).isEqualTo("TIME_RANGE_UNSUPPORTED");
        }
        jdbc.sql("UPDATE sys_user SET role_code='AUDITOR' WHERE id=:id").param("id", owner).update();
        assertThat(service.collect(1L, "最新指标日志", lease, () -> {}).status()).isEqualTo("FORBIDDEN");
        verify(metrics, never()).query(any()); verify(logs, never()).query(any());
    }

    @Test void staleAndFutureSamplesShouldNotBePresentedAsCurrentOrSystemHealth() {
        doAnswer(call -> {
            var q = call.<MetricsProvider.MetricsQuery>getArgument(0);
            return new MetricsProvider.MetricsResult("test-metrics", "fixed", null, List.of(
                    new MetricsProvider.MetricSample("old", q.resourceCode(), "old", "1", "raw", Map.of(), q.start().minusSeconds(1)),
                    new MetricsProvider.MetricSample("future", q.resourceCode(), "future", "1", "raw", Map.of(), q.end().plusSeconds(1))), List.of());
        }).when(metrics).query(any());
        var result = observations().collect(1L, "最新指标", authorization.capture(owner), () -> {});
        assertThat(result.evidence()).isEmpty(); assertThat(result.channels().get(0).status()).isEqualTo("EMPTY");
        assertThat(result.summary()).contains("不代表系统健康", "已截断/排除").doesNotContain("[metric:");
        verify(logs, never()).query(any());
    }

    @Test void sampleBudgetShouldBeTwentyWithExplicitWarning() {
        doAnswer(call -> {
            var q = call.<MetricsProvider.MetricsQuery>getArgument(0);
            var samples = new ArrayList<MetricsProvider.MetricSample>();
            for (int i = 0; i < 25; i++) samples.add(new MetricsProvider.MetricSample("" + i, q.resourceCode(), "cpu", "1", "raw", Map.of(), q.end()));
            return new MetricsProvider.MetricsResult("test-metrics", "fixed", null, samples, List.of());
        }).when(metrics).query(any());
        var result = observations().collect(1L, "指标", authorization.capture(owner), () -> {});
        assertThat(result.evidence()).hasSize(20); assertThat(result.summary()).contains("已截断", "不是总体计数");
    }

    @Test void failureAndEmptyShouldRemainDistinctWithoutErrorBodyLeak() {
        doThrow(new IllegalStateException("token=OBS_SECRET")).when(metrics).query(any());
        doReturn(new LogsProvider.LogsResult("test-logs", "fixed", null, List.of(), 0, List.of())).when(logs).query(any());
        var result = observations().collect(1L, "指标日志", authorization.capture(owner), () -> {});
        assertThat(result.channels()).extracting(AssistantObservationService.Channel::status).containsExactly("FAILED", "EMPTY");
        assertThat(result.summary()).contains("不能解释为无数据", "未返回样本").doesNotContain("OBS_SECRET");
    }

    @Test void providerFailoverShouldDiscloseActualSourceAndWarning() {
        var failed = mock(MetricsProvider.class); when(failed.available()).thenReturn(true);
        when(failed.id()).thenReturn("external"); when(failed.priority()).thenReturn(100);
        when(failed.query(any())).thenThrow(new IllegalStateException("token=OBS_SECRET"));
        var router = new MetricsProviderRouter(List.of(metrics, failed), tracing);
        var result = observations(router).collect(1L, "指标", authorization.capture(owner), () -> {});
        assertThat(result.channels().get(0).provider()).isEqualTo("test-metrics");
        assertThat(result.summary()).contains("external unavailable (IllegalStateException)").doesNotContain("OBS_SECRET");
    }

    @Test void cancellationDuringFailedProviderShouldStopFallbackLogsAndModel() {
        var stopped = new AtomicBoolean(); var failed = mock(MetricsProvider.class);
        when(failed.available()).thenReturn(true); when(failed.id()).thenReturn("external"); when(failed.priority()).thenReturn(100);
        when(failed.query(any())).thenAnswer(call -> { stopped.set(true); throw new IllegalStateException("failed"); });
        var ai = mock(AssistantAiService.class); var identity = identity();
        var service = withAi(ai, observations(new MetricsProviderRouter(List.of(failed, metrics), tracing)));
        assertThatThrownBy(() -> service.sendMessage(session, owner, "最新指标日志", authorization.capture(owner), () -> {
            if (stopped.get()) throw new ApiException(HttpStatus.CONFLICT, "ASSISTANT_REQUEST_CANCELLED", "controlled");
        }, identity)).isInstanceOf(ApiException.class);
        verify(metrics, never()).query(any()); verify(logs, never()).query(any()); verifyNoInteractions(ai);
        assertThat(assistant.getSession(session, owner).messages()).hasSize(1);
        assertThat(assistant.requestStatus(session, owner, identity.keyHash()).status()).isEqualTo("CANCELLED");
    }

    @Test void roleDowngradeDuringMetricCallShouldStopLogsAndAnswer() {
        doAnswer(call -> { jdbc.sql("UPDATE sys_user SET role_code='AUDITOR' WHERE id=:id").param("id", owner).update();
            throw new IllegalStateException("provider failed too"); }).when(metrics).query(any());
        var identity = identity(); var ai = mock(AssistantAiService.class);
        assertThatThrownBy(() -> withAi(ai, observations()).sendMessage(session, owner, "最新指标日志", authorization.capture(owner), () -> {}, identity))
                .isInstanceOf(CredentialsExpiredException.class);
        verify(logs, never()).query(any()); verifyNoInteractions(ai);
        assertThat(assistant.getSession(session, owner).messages()).hasSize(1);
        assertThat(assistant.requestStatus(session, owner, identity.keyHash()).status()).isEqualTo("REVOKED");
    }

    @Test void keyedReplayShouldReturnOriginalSnapshotWithoutAnotherProviderCall() {
        var service = withAi(null, observations()); var identity = identity(); var lease = authorization.capture(owner);
        var answer = service.sendMessage(session, owner, "最新指标日志", lease, () -> {}, identity);
        assertThat(service.sendMessage(session, owner, "最新指标日志", lease, () -> {}, identity)).isEqualTo(answer);
        verify(metrics, times(1)).query(any()); verify(logs, times(1)).query(any());
    }

    @Test void normalAndNativeModelsShouldReceiveSameActualEvidenceAndGuardCrossFrameReferences() {
        String text = "观察 [metric:test-metrics:1]；[log:test-logs:1]。";
        var inputs = new ArrayList<String>(); var ai = mock(AssistantAiService.class);
        when(ai.answer(anyString(), anyString(), anyString())).thenAnswer(c -> { inputs.add(c.getArgument(0)); return text; });
        when(ai.streamAnswer(anyString(), anyString(), anyString())).thenAnswer(c -> { inputs.add(c.getArgument(0)); return Flux.just("观察 [met", "ric:test-metrics:1]；[lo", "g:test-logs:1]。"); });
        var service = withAi(ai, observations());
        var normal = service.sendMessage(session, owner, "故障窗口指标日志");
        long next = assistant.createSession(owner, 1L, null).session().id(); var output = new StringBuilder();
        var streamed = service.streamMessage(next, owner, "故障窗口指标日志", authorization.capture(owner), () -> {}, null, output::append);
        assertThat(inputs).hasSize(2); assertThat(inputs.get(0).replaceAll("\"searchId\":\\d+", "\"searchId\":0"))
                .isEqualTo(inputs.get(1).replaceAll("\"searchId\":\\d+", "\"searchId\":0")).contains("cpu_usage=83", "INCIDENT_WINDOW", "test-logs")
                .doesNotContain("OBS_SECRET", "user@example.com");
        assertThat(normal.content()).isEqualTo(text); assertThat(streamed.content()).isEqualTo(output.toString()).isEqualTo(text);
        assertThat(streamed.evidenceJson()).isEqualTo(normal.evidenceJson());
    }

    @Test void inventedMetricOrLogReferenceShouldFailBothPathsAndNotCommitAnswer() {
        for (boolean stream : List.of(false, true)) {
            var ai = mock(AssistantAiService.class); when(ai.answer(anyString(), anyString(), anyString())).thenReturn("metric:test-metrics:99");
            when(ai.streamAnswer(anyString(), anyString(), anyString())).thenReturn(Flux.just("安全预览 [lo", "g:test-logs:99]"));
            long next = assistant.createSession(owner, 1L, null).session().id(); var output = new StringBuilder(); var identity = identity();
            var service = withAi(ai, observations());
            assertThatThrownBy(() -> {
                if (stream) service.streamMessage(next, owner, "指标日志", authorization.capture(owner), () -> {}, identity, output::append);
                else service.sendMessage(next, owner, "指标日志", authorization.capture(owner), () -> {}, identity);
            }).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ASSISTANT_CITATION_INVALID"));
            assertThat(output.toString()).doesNotContain("log:", "metric:");
            assertThat(assistant.getSession(next, owner).messages()).hasSize(1);
            assertThat(assistant.requestStatus(next, owner, identity.keyHash()).status()).isEqualTo("FAILED");
        }
    }

    @Test void historicalObservationsShouldBeFilteredFromNewModelAfterRoleDowngradeButRemainReplayable() {
        var service = withAi(null, observations()); var identity = identity(); var lease = authorization.capture(owner);
        var original = service.sendMessage(session, owner, "指标日志", lease, () -> {}, identity);
        jdbc.sql("UPDATE sys_user SET role_code='AUDITOR' WHERE id=:id").param("id", owner).update();
        var ai = mock(AssistantAiService.class);
        when(ai.answer(anyString(), anyString(), anyString())).thenAnswer(call -> {
            assertThat(call.<String>getArgument(1)).contains("取证权限不足").doesNotContain("cpu_usage=83", "log:test-logs:1");
            return "无权限，未查询运维数据";
        });
        withAi(ai, observations()).sendMessage(session, owner, "继续分析");
        assertThat(service.sendMessage(session, owner, "指标日志", lease, () -> {}, identity)).isEqualTo(original);
        assertThat(assistant.getSession(session, owner).messages()).contains(original);
    }

    @Test void roleDowngradeInsideModelShouldFencePersistence() {
        var ai = mock(AssistantAiService.class);
        when(ai.answer(anyString(), anyString(), anyString())).thenAnswer(call -> {
            jdbc.sql("UPDATE sys_user SET role_code='AUDITOR' WHERE id=:id").param("id", owner).update(); return "metric:test-metrics:1";
        });
        assertThatThrownBy(() -> withAi(ai, observations()).sendMessage(session, owner, "指标", authorization.capture(owner), () -> {}))
                .isInstanceOf(CredentialsExpiredException.class);
        assertThat(assistant.getSession(session, owner).messages()).hasSize(1);
    }

    private AssistantObservationService observations() { return observations(new MetricsProviderRouter(List.of(metrics), tracing)); }
    private AssistantObservationService observations(MetricsProviderRouter router) {
        return new AssistantObservationService(incidents, contexts, router, new LogsProviderRouter(List.of(logs), tracing), new LogRedactor(), authorization);
    }
    private AssistantService withAi(AssistantAiService ai, AssistantObservationService observation) {
        return new AssistantService(jdbc, json, Optional.ofNullable(ai), agentRuns, authorization, transactions, requests, runbooks, observation,
                new AssistantIdentity(Optional.ofNullable(ai), Optional.empty()));
    }
    private AssistantRequestStore.Identity identity() {
        return new AssistantRequestStore.Identity(AssistantRequestStore.keyHash(UUID.randomUUID().toString()), UUID.randomUUID().toString(), System.currentTimeMillis() + 30000);
    }
}
