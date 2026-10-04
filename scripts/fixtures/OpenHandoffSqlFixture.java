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
        Path database = Path.of(args[0]).toAbsolutePath().normalize();
        if (!root.getFileName().toString().startsWith("run-")
                || !root.getParent().getFileName().toString().equals("oncall-open-handoff-http-it")
                || !database.getParent().toRealPath().equals(root.resolve("database").toRealPath())
                || !database.getFileName().toString().equals("opspilot")
                || !Files.isRegularFile(Path.of(database + ".mv.db"))
                || !Path.of(database + ".mv.db").toRealPath().getParent().equals(database.getParent().toRealPath())) {
            throw new IllegalArgumentException("Require existing runner-owned database, never create or open user data");
        }
        String url = "jdbc:h2:file:" + database.toString().replace('\\', '/')
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0;IFEXISTS=TRUE";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            if (!connection.getMetaData().getDatabaseProductName().equals("H2")) throw new IllegalStateException("Require actual H2");
            if (args[1].equals("DEMOTE")) {
                try (var statement = connection.prepareStatement("UPDATE sys_user SET role_code='AUDITOR' WHERE id=3 AND username='lina' AND role_code='OPS_MANAGER'")) {
                    if (statement.executeUpdate() != 1) throw new IllegalStateException("Require exact seeded role transition");
                }
                System.out.println("{\"actualProduct\":\"H2\",\"actorId\":3,\"changed\":1,\"role\":\"AUDITOR\"}");
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
                    """)) {
                while (rows.next()) requests.add("{\"id\":" + rows.getLong("id") + ",\"sourceShiftId\":" + rows.getLong("source_shift_id")
                        + ",\"status\":" + quote(rows.getString("status")) + ",\"version\":" + rows.getInt("version")
                        + ",\"claimedBy\":" + rows.getObject("claimed_by") + ",\"replacementShiftId\":" + rows.getObject("replacement_shift_id")
                        + ",\"operations\":" + rows.getLong("operations") + ",\"audits\":" + rows.getLong("audits")
                        + ",\"overrides\":" + rows.getLong("overrides") + "}");
            }
            String currentHash = migrated ? fingerprint(connection, "SELECT * FROM oncall_open_handoff ORDER BY id") : null;
            String operationHash = migrated ? fingerprint(connection, "SELECT * FROM oncall_open_handoff_operation ORDER BY handoff_id") : null;
            System.out.println("{\"actualProduct\":\"H2\",\"actualVersion\":" + quote(connection.getMetaData().getDatabaseProductVersion())
                    + ",\"migrationCount\":" + count(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL")
                    + ",\"successfulHistoryRows\":" + count(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE")
                    + ",\"history\":[" + String.join(",", history) + "]"
                    + ",\"migration38\":" + migrated + ",\"shifts\":" + count(connection, "SELECT COUNT(*) FROM oncall_shift")
                    + ",\"shiftHash\":" + quote(fingerprint(connection, "SELECT * FROM oncall_shift ORDER BY id"))
                    + ",\"designatedHash\":" + quote(fingerprint(connection, "SELECT * FROM oncall_handoff ORDER BY id"))
                    + ",\"swapHash\":" + quote(fingerprint(connection, "SELECT * FROM oncall_shift_swap ORDER BY id"))
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
