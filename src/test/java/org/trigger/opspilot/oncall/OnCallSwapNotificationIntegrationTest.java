package org.trigger.opspilot.oncall;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:opspilot-swap-notification;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
        "opspilot.ai.enabled=false","opspilot.oncall.rotation.enabled=false","opspilot.oncall.escalation.enabled=false",
        "opspilot.oncall.swap.notification.enabled=true","opspilot.oncall.swap.notification.token=cp87-notification-token",
        "opspilot.oncall.swap.notification.connect-timeout=500ms","opspilot.oncall.swap.notification.read-timeout=5s",
        "opspilot.oncall.swap.notification.lease=10s","opspilot.oncall.swap.notification.retry-base-delay=5s",
        "opspilot.oncall.swap.notification.retry-max-delay=30s","opspilot.oncall.swap.notification.max-attempts=3",
        "ONCALL_SWAP_NOTIFICATION_DISPATCH_INITIAL_DELAY=600000","ONCALL_SWAP_NOTIFICATION_DISPATCH_DELAY=600000"})
class OnCallSwapNotificationIntegrationTest extends SwapNotificationScenarios {}
