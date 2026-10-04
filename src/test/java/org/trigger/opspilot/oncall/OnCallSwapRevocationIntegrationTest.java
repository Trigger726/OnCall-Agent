package org.trigger.opspilot.oncall;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:opspilot-swap-revocation-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
        "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
        "opspilot.ai.enabled=false","opspilot.oncall.rotation.enabled=false","opspilot.oncall.escalation.enabled=false",
        "opspilot.oncall.swap.notification.enabled=true","opspilot.oncall.swap.notification.url=http://127.0.0.1:65534/owned-not-dispatched",
        "opspilot.oncall.swap.notification.token=cp92-local-test-only","ONCALL_SWAP_NOTIFICATION_DISPATCH_INITIAL_DELAY=86400000"})
class OnCallSwapRevocationIntegrationTest extends SwapRevocationScenarios {}
