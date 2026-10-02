package org.trigger.opspilot.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthSessionMigrationTest {
    @Test void shouldPreserveV30AccountsAndOnlyInitializeRevocationVersion() throws Exception {
        String url = "jdbc:h2:mem:auth-upgrade-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("30").load().migrate();
        List<List<Object>> before = accounts(url);
        assertThat(before).isNotEmpty();
        assertThat(Flyway.configure().dataSource(url, "sa", "").target("31").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(accounts(url)).containsExactlyElementsOf(before);
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM sys_user WHERE auth_version=0")) {
                rows.next(); assertThat(rows.getLong(1)).isEqualTo(before.size());
            }
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE sys_user SET auth_version=-1 WHERE username='admin'"))
                    .isInstanceOf(java.sql.SQLException.class);
            try (var rows = statement.executeQuery("SELECT auth_version FROM sys_user WHERE username='admin'")) {
                rows.next(); assertThat(rows.getLong(1)).isZero();
            }
        }
        assertThat(Flyway.configure().dataSource(url, "sa", "").target("31").load().migrate().migrationsExecuted).isZero();
    }

    private static List<List<Object>> accounts(String url) throws Exception {
        List<List<Object>> result = new ArrayList<>();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id,username,password_hash,display_name,role_code,status,created_at,updated_at FROM sys_user ORDER BY id")) {
            while (rows.next()) result.add(List.of(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4),
                    rows.getString(5), rows.getString(6), rows.getTimestamp(7), rows.getTimestamp(8)));
        }
        return result;
    }
}
