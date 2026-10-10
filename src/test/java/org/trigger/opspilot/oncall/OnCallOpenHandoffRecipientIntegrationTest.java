package org.trigger.opspilot.oncall;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:opspilot-open-recipient-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
        "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
        "opspilot.ai.enabled=false","opspilot.oncall.rotation.enabled=false","opspilot.oncall.escalation.enabled=false"})
class OnCallOpenHandoffRecipientIntegrationTest extends OpenHandoffRecipientScenarios {}
