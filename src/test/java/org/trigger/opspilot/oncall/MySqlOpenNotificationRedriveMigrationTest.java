package org.trigger.opspilot.oncall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MySQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
@EnabledIfSystemProperty(named="opspilot.mysql.it.enabled",matches="true")
class MySqlOpenNotificationRedriveMigrationTest {
    @Test @Timeout(120) void shouldPreserveAllPopulatedV41StatesAndChecksumsAcrossV42() throws Exception {
        try(var mysql=new MySQLContainer<>("mysql:8.4").withDatabaseName("opspilot_redrive_migration_test").withUsername("opspilot").withPassword("opspilot-test").withCommand("--character-set-server=utf8mb4","--collation-server=utf8mb4_unicode_ci")) {
            mysql.start();OpenNotificationRedriveMigrationScenarios.verify(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword(),"MySQL","opspilot_redrive_migration_test");
            mysql.stop();assertThat(mysql.isRunning()).isFalse();System.out.println("OPEN_NOTIFICATION_REDRIVE_MIGRATION_CONTAINER_STOPPED {\"stopped\":true}");
        }
    }
}
