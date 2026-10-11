package org.trigger.opspilot.runbook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named="opspilot.scoped.eval.mysql.enabled",matches="true")
class MySqlRunbookRetrievalScopeMigrationTest {
    @Test void shouldPreserveNonemptyV42HistoryOnMySql() throws Exception {
        String url=System.getenv("OPSPILOT_SCOPED_MYSQL_UPGRADE_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/opspilot_scope_upgrade(?:\\?.*)?");
        String user=System.getenv("OPSPILOT_SCOPED_MYSQL_USER");
        String password=System.getenv("OPSPILOT_SCOPED_MYSQL_PASSWORD");
        assertThat(user).isNotBlank();
        assertThat(password).isNotBlank();
        RunbookRetrievalScopeMigrationTest.verifyUpgrade(url,user,password);
    }
}
