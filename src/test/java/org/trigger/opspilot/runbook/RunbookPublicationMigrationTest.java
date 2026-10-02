package org.trigger.opspilot.runbook;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

class RunbookPublicationMigrationTest {
    @Test void shouldPreservePublishedV29ContentAndHistory() throws Exception {
        String url = "jdbc:h2:mem:runbook-publication-upgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("29").load().migrate();
        java.util.List<String> before = new java.util.ArrayList<>();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var rows = s.executeQuery("SELECT id,status,markdown_content,published_at FROM runbook_document ORDER BY id")) {
                while (rows.next()) before.add(rows.getLong(1) + "|" + rows.getString(2) + "|" + rows.getString(3) + "|" + rows.getTimestamp(4));
            }
        }
        assertThat(before).hasSize(6);
        assertThat(Flyway.configure().dataSource(url, "sa", "").target("30").load().migrate().migrationsExecuted).isEqualTo(1);
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            java.util.List<String> after = new java.util.ArrayList<>();
            try (var rows = s.executeQuery("SELECT id,status,markdown_content,published_at,review_version,decision_key FROM runbook_document ORDER BY id")) {
                while (rows.next()) {
                    after.add(rows.getLong(1) + "|" + rows.getString(2) + "|" + rows.getString(3) + "|" + rows.getTimestamp(4));
                    assertThat(rows.getInt(5)).isZero(); assertThat(rows.getString(6)).isNull();
                }
            }
            assertThat(after).containsExactlyElementsOf(before);
            try (var rows = s.executeQuery("SELECT COUNT(*) FROM runbook_publication_lock")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(6);
            }
        }
    }
}
