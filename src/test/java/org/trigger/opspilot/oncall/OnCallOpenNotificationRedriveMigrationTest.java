package org.trigger.opspilot.oncall;
import org.junit.jupiter.api.Test;
class OnCallOpenNotificationRedriveMigrationTest {
    @Test void shouldPreserveAllPopulatedV41StatesAndChecksumsAcrossV42() throws Exception {
        OpenNotificationRedriveMigrationScenarios.verify("jdbc:h2:mem:opspilot-redrive-migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","","H2","opspilot-redrive-migration");
    }
}
