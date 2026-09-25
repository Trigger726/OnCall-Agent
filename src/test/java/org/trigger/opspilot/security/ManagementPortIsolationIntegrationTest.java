package org.trigger.opspilot.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-management-isolation-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false",
        "management.server.port=0",
        "management.server.address=127.0.0.1"
})
class ManagementPortIsolationIntegrationTest {
    @LocalServerPort private int applicationPort;
    @LocalManagementPort private int managementPort;

    @Test
    void shouldServeMetricsOnlyOnSeparateManagementListener() throws Exception {
        assertThat(applicationPort).isNotEqualTo(managementPort);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpResponse<String> application = get(client, applicationPort, "/actuator/prometheus");
        HttpResponse<String> metrics = get(client, managementPort, "/actuator/prometheus");
        HttpResponse<String> health = get(client, managementPort, "/actuator/health");

        assertThat(application.statusCode()).isEqualTo(404);
        assertThat(application.body()).doesNotContain("# HELP jvm_");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("# HELP jvm_");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }

    private static HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
