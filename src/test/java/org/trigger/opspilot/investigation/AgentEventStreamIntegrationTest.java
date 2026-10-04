package org.trigger.opspilot.investigation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-event-stream;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false",
        "opspilot.agent.events.catchup-delay=3600000"
})
// MockMvc's default failure-log collector iterates response headers while the async SSE
// converter is writing them. Assert headers explicitly below; do not run that unsafe printer.
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AgentEventStreamIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired InvestigationService investigations;
    @Autowired AgentRunEventService events;
    @Autowired AgentEventSubscriptions subscriptions;
    @Autowired SecurityFilterChain security;
    @Autowired org.trigger.opspilot.security.JwtService jwt;
    @Autowired org.trigger.opspilot.security.OpsUserDetailsService users;

    @Test
    void shouldWriteSecurityHeadersBeforeSseWorkStartsAndRetainStreamCachePolicy() throws Exception {
        var filter = security.getFilters().stream().filter(HeaderWriterFilter.class::isInstance)
                .map(HeaderWriterFilter.class::cast).findFirst().orElseThrow();
        var request = new MockHttpServletRequest("GET", path(1));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            // A deterministic regression: no async sender may start before these exist.
            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
            response.setHeader("Cache-Control", "no-cache"); // SSE controller's own policy
        });
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void shouldResumeViaHeaderAndCloseWhenTerminalAlreadyConsumed() throws Exception {
        long runId = prepare();
        long queued = events.list(runId, 0).get(0).id();
        var terminal = finish(runId);
        String path = path(runId);
        var result = mvc.perform(get(path).header("Authorization", bearer())
                        .header("Last-Event-ID", queued))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andExpect(header().string("Cache-Control", "no-cache"));
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("id:" + terminal.id(), "event:run_completed")
                .doesNotContain("event:run_queued");
        var consumed = mvc.perform(get(path).header("Authorization", bearer())
                        .param("after", Long.toString(terminal.id())))
                .andExpect(request().asyncStarted()).andReturn();
        consumed.getAsyncResult(5000);
        mvc.perform(asyncDispatch(consumed)).andExpect(status().isOk());
        assertThat(consumed.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void shouldCatchUpActiveSubscriptionWithoutRedisAndHonorQueryCursor() throws Exception {
        long runId = prepare();
        var result = mvc.perform(get(path(runId)).header("Authorization", bearer())
                        .param("after", "0").header("Last-Event-ID", Long.MAX_VALUE))
                .andExpect(request().asyncStarted()).andReturn();
        var terminal = finish(runId);
        subscriptions.catchUp();
        result.getAsyncResult(5000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:run_queued", "id:" + terminal.id());
        assertThat(body.indexOf("event:run_queued")).isLessThan(body.indexOf("event:run_completed"));
        assertThat(body.split("event:run_completed", -1)).hasSize(2);
    }

    @Test
    void shouldProtectEndpointAndRejectForeignOrInvalidCursors() throws Exception {
        long runId = prepare();
        long foreign = events.list(prepare(), 0).get(0).id();
        mvc.perform(get(path(runId))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(Long.MAX_VALUE)).header("Authorization", bearer())).andExpect(status().isNotFound());
        for (String cursor : new String[]{"-1", "garbage", Long.toString(foreign), Long.toString(Long.MAX_VALUE)}) {
            mvc.perform(get(path(runId)).header("Authorization", bearer()).param("after", cursor))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void shouldAllowRecoveryHeadersAcrossConfiguredOrigin() throws Exception {
        mvc.perform(options(path(1)).header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization,last-event-id,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(get("/api/v1/agent-runs/1/events").header("Authorization", bearer())
                        .header("Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Expose-Headers",
                        "X-OpsPilot-Run-Id, X-OpsPilot-Idempotent-Replay"));
    }

    private long prepare() {
        return investigations.prepare(1, "STREAM_TEST", new InvestigationService.RunActor(1L, "127.0.0.1"),
                UUID.randomUUID().toString(), Duration.ofSeconds(30)).runId();
    }

    private AgentRunEventService.EventView finish(long runId) {
        return events.record(runId, "RUN_COMPLETED", "FINISH", null, "COMPLETED",
                Map.of("test", true), AgentRunEventService.EventSink.NOOP);
    }

    private static String path(long runId) { return "/api/v1/agent-runs/" + runId + "/events/stream"; }
    private String bearer() { return "Bearer " + jwt.createToken(users.loadUserByUsername("auditor")); }
}
