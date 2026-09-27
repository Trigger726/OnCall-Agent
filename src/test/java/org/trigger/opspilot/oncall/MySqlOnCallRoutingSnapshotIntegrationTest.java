package org.trigger.opspilot.oncall;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;

@EnabledIfSystemProperty(named = "opspilot.mysql.it.enabled", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
        "spring.h2.console.enabled=false", "opspilot.ai.enabled=false",
        "opspilot.oncall.escalation.enabled=false"
})
class MySqlOnCallRoutingSnapshotIntegrationTest extends RoutingSnapshotScenarios {
    // Spring owns the container for the entire cached context, including bean shutdown.
    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        MySQLContainer<?> mysql() {
            return new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("opspilot_snapshot_test")
                .withUsername("opspilot").withPassword("opspilot-test")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
        }
    }
}
