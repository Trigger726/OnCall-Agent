package org.trigger.opspilot.oncall;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-routing-snapshot-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
        "opspilot.ai.enabled=false", "opspilot.oncall.escalation.enabled=false"
})
class OnCallRoutingSnapshotIntegrationTest extends RoutingSnapshotScenarios {
}
