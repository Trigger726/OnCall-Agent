package org.trigger.opspilot.investigation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.trigger.opspilot.incident.IncidentService;
import org.trigger.opspilot.investigation.tool.InvestigationTool;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-cancel-race;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false"
})
@Import(AgentRunCancellationIntegrationTest.ControlledToolConfig.class)
class AgentRunCancellationIntegrationTest {
    @Autowired
    private InvestigationService investigationService;

    @Autowired
    private AgentRunEventService eventService;

    @Autowired
    private ControlledTool controlledTool;

    @Test
    void shouldFinishAsCancelledWhenToolReturnsAfterCancellationRequest() throws Exception {
        InvestigationService.RunActor actor = new InvestigationService.RunActor(1L, "127.0.0.1");
        InvestigationService.PreparedRun prepared = investigationService.prepare(
                1, "CANCEL_RACE_TEST", actor, "cancel-race-" + UUID.randomUUID(), Duration.ofSeconds(30));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<InvestigationService.InvestigationResult> future = executor.submit(() ->
                    investigationService.execute(prepared, actor, AgentRunEventService.EventSink.NOOP));
            assertThat(controlledTool.entered.await(3, TimeUnit.SECONDS)).isTrue();

            investigationService.requestCancellation(prepared.runId(), actor,
                    "工具返回边界取消竞态测试");
            controlledTool.release.countDown();

            InvestigationService.InvestigationResult result = future.get(5, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo("CANCELLED");
            assertThat(result.reportId()).isNull();
            AgentRunQueryService.AgentRunView run = investigationService.listRuns(1).stream()
                    .filter(item -> item.id().equals(prepared.runId())).findFirst().orElseThrow();
            assertThat(run.status()).isEqualTo("CANCELLED");
            assertThat(run.steps()).extracting(AgentRunQueryService.AgentStepView::sequence)
                    .doesNotHaveDuplicates();
            List<AgentRunEventService.EventView> events = eventService.list(prepared.runId(), 0);
            assertThat(events.get(events.size() - 1).eventType()).isEqualTo("RUN_CANCELLED");
        } finally {
            controlledTool.release.countDown();
            executor.shutdownNow();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledToolConfig {
        @Bean
        ControlledTool controlledTool() {
            return new ControlledTool();
        }
    }

    static final class ControlledTool implements InvestigationTool {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public int order() {
            return 26;
        }

        @Override
        public String name() {
            return "controlled_cancel_boundary";
        }

        @Override
        public String title() {
            return "等待取消边界";
        }

        @Override
        public ToolResult execute(IncidentService.IncidentDetail incident) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for cancellation test release");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Cancellation test tool interrupted", exception);
            }
            return new ToolResult("取消请求后工具完成返回", List.of());
        }
    }
}
