package org.trigger.opspilot.oncall;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-coverage-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "opspilot.ai.enabled=false", "opspilot.oncall.rotation.enabled=false", "opspilot.oncall.escalation.enabled=false"
})
class OnCallCoverageIntegrationTest extends CoverageScenarios {}
