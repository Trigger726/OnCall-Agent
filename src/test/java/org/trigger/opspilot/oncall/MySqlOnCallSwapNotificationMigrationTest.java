package org.trigger.opspilot.oncall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MySQLContainer;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named="opspilot.oncall.swap.notification.migration.mysql.enabled",matches="true")
class MySqlOnCallSwapNotificationMigrationTest {
    @Test @Timeout(value=4,unit=TimeUnit.MINUTES)
    void shouldFreezeLegacyDeadlineWithoutErasingV35PayloadOrTechnicalHistory() throws Exception {
        var mysql=new MySQLContainer<>("mysql:8.4").withDatabaseName("opspilot_swap_notification_migration_test")
                .withUsername("opspilot").withPassword("opspilot-test")
                .withCommand("--character-set-server=utf8mb4","--collation-server=utf8mb4_unicode_ci","--default-time-zone=+00:00");
        try(mysql){mysql.start();SwapNotificationMigrationScenarios.verify(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());}
        assertThat(mysql.isRunning()).isFalse();
        System.out.println("CP91_SWAP_NOTIFICATION_MIGRATION_CONTAINER_STOPPED {\"case\":\""+SwapNotificationMigrationScenarios.CASE+"\",\"stopped\":true}");
    }
}
