package org.trigger.opspilot.oncall;

import org.junit.jupiter.api.Test;
import java.util.UUID;

class OnCallSwapNotificationMigrationTest {
    @Test void shouldFreezeLegacyDeadlineWithoutErasingV35PayloadOrTechnicalHistory() throws Exception {
        String url="jdbc:h2:mem:notification-upgrade-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        SwapNotificationMigrationScenarios.verify(url,"sa","");
    }
}
