import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HexFormat;

/** Offline evidence/role change, only after this runner's own JVM has stopped. */
public class OpenHandoffSqlFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].equals("SNAPSHOT") && !args[1].equals("DEMOTE")) {
            throw new IllegalArgumentException("Require owned database and exact fixture mode");
        }
        Path root = Path.of(System.getenv("OPSPILOT_OPEN_HANDOFF_FIXTURE_ROOT")).toRealPath();
        boolean mysql = args[0].equals("MYSQL_TESTCONTAINER");
        if (!root.getFileName().toString().startsWith("run-")) throw new IllegalArgumentException("Require owned evidence directory");
        String url;
        if (mysql) {
            if (!root.getParent().getFileName().toString().equals("oncall-open-handoff-upgrade-mysql-it")) throw new IllegalArgumentException("Require MySQL runner root");
            url = System.getenv("OPSPILOT_UPGRADE_JDBC_URL");
            String schema = System.getenv("OPSPILOT_UPGRADE_SCHEMA");
            if (schema == null || !schema.matches("opspilot_open_upgrade_[a-f0-9]{12}") || url == null
                    || !url.matches("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/" + schema + "\\?[^\\s]+")) {
                throw new IllegalArgumentException("Require dedicated loopback Testcontainer schema");
            }
        } else {
        Path database = Path.of(args[0]).toAbsolutePath().normalize();
        if (!root.getFileName().toString().startsWith("run-")
                || !root.getParent().getFileName().toString().equals("oncall-open-handoff-http-it")
                || !database.getParent().toRealPath().equals(root.resolve("database").toRealPath())
                || !database.getFileName().toString().equals("opspilot")
                || !Files.isRegularFile(Path.of(database + ".mv.db"))
                || !Path.of(database + ".mv.db").toRealPath().getParent().equals(database.getParent().toRealPath())) {
            throw new IllegalArgumentException("Require existing runner-owned database, never create or open user data");
        }
        url = "jdbc:h2:file:" + database.toString().replace('\\', '/')
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0;IFEXISTS=TRUE";
        }
        try (var connection = DriverManager.getConnection(url, mysql ? System.getenv("OPSPILOT_UPGRADE_DB_USER") : "sa",
                mysql ? System.getenv("OPSPILOT_UPGRADE_DB_PASSWORD") : "")) {
            String identity = "\"actualProduct\":" + quote(connection.getMetaData().getDatabaseProductName())
                    + ",\"actualVersion\":" + quote(connection.getMetaData().getDatabaseProductVersion());
            if (mysql) {
                if (!connection.getMetaData().getDatabaseProductName().equals("MySQL")
                        || !connection.getMetaData().getDatabaseProductVersion().startsWith("8.4.")
                        || !connection.getCatalog().equals(System.getenv("OPSPILOT_UPGRADE_SCHEMA"))) throw new IllegalStateException("Require actual owned MySQL 8.4");
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT @@server_uuid")) {
                    String serverUuid = System.getenv("OPSPILOT_UPGRADE_SERVER_UUID");
                    if (serverUuid == null || !rows.next() || !serverUuid.equals(rows.getString(1)) || rows.next()) {
                        throw new IllegalStateException("Require this Testcontainer owner's actual server UUID");
                    }
                    identity += ",\"serverUuid\":" + quote(serverUuid);
                }
                identity += ",\"schema\":" + quote(connection.getCatalog()) + ",\"ownerConfirmed\":true";
            } else if (!connection.getMetaData().getDatabaseProductName().equals("H2")) throw new IllegalStateException("Require actual H2");
            if (args[1].equals("DEMOTE")) {
                try (var statement = connection.prepareStatement("UPDATE sys_user SET role_code='AUDITOR' WHERE id=3 AND username='lina' AND role_code='OPS_MANAGER'")) {
                    if (statement.executeUpdate() != 1) throw new IllegalStateException("Require exact seeded role transition");
                }
                System.out.println("{" + identity + ",\"actorId\":3,\"changed\":1,\"role\":\"AUDITOR\"}");
                return;
            }
            boolean migrated = count(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE version='38' AND success=TRUE") == 1;
            var history = new ArrayList<String>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT version,type,success FROM flyway_schema_history ORDER BY installed_rank")) {
                while (rows.next()) history.add("{\"version\":" + quote(rows.getString("version"))
                        + ",\"type\":" + quote(rows.getString("type")) + ",\"success\":" + rows.getBoolean("success") + "}");
            }
            var requests = new ArrayList<String>();
            if (migrated) try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                    SELECT h.id,h.source_shift_id,h.status,h.version,h.claimed_by,h.replacement_shift_id,
                      (SELECT COUNT(*) FROM oncall_open_handoff_operation o WHERE o.handoff_id=h.id) AS operations,
                      (SELECT COUNT(*) FROM audit_log a WHERE a.target_type='ONCALL_OPEN_HANDOFF'
                         AND a.target_id=CAST(h.id AS VARCHAR)) AS audits,
                      (SELECT COUNT(*) FROM oncall_shift s WHERE s.id=h.replacement_shift_id AND s.override_flag=TRUE) AS overrides
                    FROM oncall_open_handoff h ORDER BY h.id
                    """.replace("CAST(h.id AS VARCHAR)", mysql ? "CAST(h.id AS CHAR)" : "CAST(h.id AS VARCHAR)"))) {
                while (rows.next()) requests.add("{\"id\":" + rows.getLong("id") + ",\"sourceShiftId\":" + rows.getLong("source_shift_id")
                        + ",\"status\":" + quote(rows.getString("status")) + ",\"version\":" + rows.getInt("version")
                        + ",\"claimedBy\":" + rows.getObject("claimed_by") + ",\"replacementShiftId\":" + rows.getObject("replacement_shift_id")
                        + ",\"operations\":" + rows.getLong("operations") + ",\"audits\":" + rows.getLong("audits")
                        + ",\"overrides\":" + rows.getLong("overrides") + "}");
            }
            String currentHash = migrated ? fingerprint(connection, "SELECT * FROM oncall_open_handoff ORDER BY id") : null;
            String operationHash = migrated ? fingerprint(connection, "SELECT * FROM oncall_open_handoff_operation ORDER BY handoff_id") : null;
            System.out.println("{" + identity
                    + ",\"migrationCount\":" + count(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL")
                    + ",\"successfulHistoryRows\":" + count(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE")
                    + ",\"history\":[" + String.join(",", history) + "]"
                    + ",\"migration38\":" + migrated + ",\"shifts\":" + count(connection, "SELECT COUNT(*) FROM oncall_shift")
                    + ",\"shiftHash\":" + quote(fingerprint(connection, "SELECT * FROM oncall_shift ORDER BY id"))
                    + ",\"designatedHash\":" + quote(fingerprint(connection, "SELECT * FROM oncall_handoff ORDER BY id"))
                    + ",\"swapHash\":" + quote(fingerprint(connection, "SELECT * FROM oncall_shift_swap ORDER BY id"))
                    + ",\"swapRevocationHash\":" + quote(fingerprint(connection, "SELECT * FROM oncall_swap_revocation ORDER BY swap_id"))
                    + ",\"designatedRows\":" + count(connection, "SELECT COUNT(*) FROM oncall_handoff")
                    + ",\"swapRows\":" + count(connection, "SELECT COUNT(*) FROM oncall_shift_swap")
                    + ",\"swapRevocationRows\":" + count(connection, "SELECT COUNT(*) FROM oncall_swap_revocation")
                    + ",\"legacyMigrationHash\":" + quote(fingerprint(connection, "SELECT version,type,script,checksum,success FROM flyway_schema_history WHERE version IS NOT NULL AND CAST(version AS INTEGER)<38 ORDER BY installed_rank".replace("CAST(version AS INTEGER)", mysql ? "CAST(version AS UNSIGNED)" : "CAST(version AS INTEGER)")))
                    + ",\"onCallAuditHash\":" + quote(fingerprint(connection, "SELECT * FROM audit_log WHERE action LIKE 'ONCALL_%' ORDER BY id"))
                    + ",\"openRequestHash\":" + quote(currentHash) + ",\"openOperationHash\":" + quote(operationHash)
                    + ",\"requests\":[" + String.join(",", requests) + "]}");
        }
    }
    private static long count(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { rows.next(); return rows.getLong(1); }
    }
    private static String fingerprint(Connection connection, String sql) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) for (int index = 1; index <= columns; index++) {
                String value = rows.getString(index);
                byte[] bytes = (value == null ? "NULL" : value.length() + ":" + value).getBytes(StandardCharsets.UTF_8);
                digest.update(bytes); digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
