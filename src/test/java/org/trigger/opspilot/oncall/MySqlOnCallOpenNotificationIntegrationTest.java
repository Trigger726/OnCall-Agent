package org.trigger.opspilot.oncall;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.MySQLContainer;

@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named="opspilot.mysql.it.enabled",matches="true")
@SpringBootTest(properties={"spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","spring.h2.console.enabled=false",
        "opspilot.ai.enabled=false","opspilot.oncall.rotation.enabled=false","opspilot.oncall.escalation.enabled=false"})
class MySqlOnCallOpenNotificationIntegrationTest extends OpenNotificationScenarios {
    @TestConfiguration(proxyBeanMethods=false)
    static class Containers {
        @Bean @ServiceConnection MySQLContainer<?> mysql(){return new MySQLContainer<>("mysql:8.4").withDatabaseName("opspilot_open_notification_test")
                .withUsername("opspilot").withPassword("opspilot-test").withCommand("--character-set-server=utf8mb4","--collation-server=utf8mb4_unicode_ci");}
    }
}
