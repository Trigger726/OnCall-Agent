package org.trigger.opspilot.slo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-slo-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false",
        "opspilot.agent.recovery.enabled=false",
        "opspilot.observability.prometheus.enabled=true",
        "opspilot.observability.reliability.max-attempts=1"
})
@AutoConfigureMockMvc
@Transactional
class ServiceSloIntegrationTest {
    private static final HttpServer PROMETHEUS = startPrometheus();
    private static final AtomicReference<String> GOOD_EVENTS = new AtomicReference<>("9950");
    private static final AtomicReference<String> TOTAL_EVENTS = new AtomicReference<>("10000");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void prometheusProperties(DynamicPropertyRegistry registry) {
        registry.add("opspilot.observability.prometheus.base-url",
                () -> "http://127.0.0.1:" + PROMETHEUS.getAddress().getPort());
    }

    @AfterAll
    static void stopPrometheus() {
        PROMETHEUS.stop(0);
    }

    @BeforeEach
    void resetPrometheusValues() {
        GOOD_EVENTS.set("9950");
        TOTAL_EVENTS.set("10000");
    }

    @Test
    void shouldCalculateRealEventRatioAndErrorBudget() throws Exception {
        String token = login("lina");

        mockMvc.perform(get("/api/v1/slo/objectives")
                        .header("Authorization", bearer(token))
                        .param("at", "2026-09-21T13:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prometheusEnabled").value(true))
                .andExpect(jsonPath("$.data.objectives.length()").value(2))
                .andExpect(jsonPath("$.data.objectives[0].serviceCode").value("APP-AUTH"))
                .andExpect(jsonPath("$.data.objectives[0].measurement.status").value("BREACHED"))
                .andExpect(jsonPath("$.data.objectives[0].measurement.goodEvents").value(9950.0))
                .andExpect(jsonPath("$.data.objectives[0].measurement.totalEvents").value(10000.0))
                .andExpect(jsonPath("$.data.objectives[0].measurement.sliPercent").value(99.5))
                .andExpect(jsonPath("$.data.objectives[0].measurement.errorBudgetEvents").value(10.0))
                .andExpect(jsonPath("$.data.objectives[0].measurement.remainingEvents").value(-40.0))
                .andExpect(jsonPath("$.data.objectives[0].measurement.consumedPercent").value(500.0));
    }

    @Test
    void shouldRequireManagerAndOptimisticVersionWhenUpdatingObjective() throws Exception {
        String manager = login("lina");
        String onCall = login("zhangwei");
        String body = """
                {
                  "expectedVersion":0,
                  "name":"成功请求比例",
                  "targetPercent":99.0,
                  "windowDays":28,
                  "goodEventsQueryTemplate":"sum(increase(good{service=\\\"{{service}}\\\"}[{{window}}]))",
                  "totalEventsQueryTemplate":"sum(increase(total{service=\\\"{{service}}\\\"}[{{window}}]))"
                }
                """;

        mockMvc.perform(patch("/api/v1/slo/objectives/1")
                        .header("Authorization", bearer(onCall))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/slo/objectives/1")
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetPercent").value(99.0))
                .andExpect(jsonPath("$.data.windowDays").value(28))
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(patch("/api/v1/slo/objectives/1")
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SLO_VERSION_CONFLICT"));
    }

    @Test
    void shouldRejectQueryThatDoesNotUseConfiguredWindow() throws Exception {
        String token = login("lina");
        mockMvc.perform(patch("/api/v1/slo/objectives/1")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"name":"成功请求比例","targetPercent":99,
                                 "windowDays":30,"goodEventsQueryTemplate":"sum(good)",
                                 "totalEventsQueryTemplate":"sum(total[{{window}}])"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SLO_QUERY_WINDOW_REQUIRED"));
    }

    @Test
    void shouldRefuseToCalculateFromZeroDenominatorOrContradictoryCounts() throws Exception {
        String token = login("lina");
        TOTAL_EVENTS.set("0");
        mockMvc.perform(get("/api/v1/slo/objectives").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.objectives[0].measurement.status").value("NO_DATA"))
                .andExpect(jsonPath("$.data.objectives[0].measurement.sliPercent").isEmpty());

        GOOD_EVENTS.set("10001");
        TOTAL_EVENTS.set("10000");
        mockMvc.perform(get("/api/v1/slo/objectives").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.objectives[0].measurement.status").value("INVALID_DATA"))
                .andExpect(jsonPath("$.data.objectives[0].measurement.sliPercent").isEmpty());
    }

    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", "OpsPilot@2026"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static HttpServer startPrometheus() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/query", ServiceSloIntegrationTest::respond);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String query = parameters(exchange).getOrDefault("query", "");
        String value = query.contains("good") ? GOOD_EVENTS.get() : TOTAL_EVENTS.get();
        byte[] body = ("{\"status\":\"success\",\"data\":{\"resultType\":\"scalar\","
                + "\"result\":[1789952400,\"" + value + "\"]}}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static Map<String, String> parameters(HttpExchange exchange) {
        return Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                .map(item -> item.split("=", 2)).collect(Collectors.toMap(
                        parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
    }
}
