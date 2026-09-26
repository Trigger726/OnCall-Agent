package org.trigger.opspilot.oncall;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@EnabledIfSystemProperty(named = "opspilot.mysql.it.enabled", matches = "true")
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver", "spring.h2.console.enabled=false",
        "opspilot.ai.enabled=false", "opspilot.oncall.rotation.enabled=false", "opspilot.oncall.escalation.enabled=false"
})
class MySqlOnCallRotationIntegrationTest extends RotationScenarios {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("opspilot_rotation_test").withUsername("opspilot").withPassword("opspilot-test")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
}
