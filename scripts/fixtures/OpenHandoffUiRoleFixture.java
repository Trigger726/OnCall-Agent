import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HexFormat;

/** Offline role fixture restricted to this UI runner's existing isolated database. */
public class OpenHandoffUiRoleFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !java.util.Set.of("SNAPSHOT", "DEMOTE", "RESTORE").contains(args[1])) {
            throw new IllegalArgumentException("Require exact owned database and fixture mode");
        }
        Path root = Path.of(System.getenv("OPSPILOT_OPEN_UI_FIXTURE_ROOT")).toRealPath();
        Path database = Path.of(args[0]).toAbsolutePath().normalize();
        if (!root.getFileName().toString().startsWith("run-")
                || !root.getParent().getFileName().toString().equals("oncall-open-handoff-ui-it")
                || !database.getParent().toRealPath().equals(root.resolve("database").toRealPath())
                || !database.getFileName().toString().equals("opspilot")
                || !Files.isRegularFile(Path.of(database + ".mv.db"))
                || !Path.of(database + ".mv.db").toRealPath().getParent().equals(database.getParent().toRealPath())) {
            throw new IllegalArgumentException("Require existing runner-owned database; never open user data");
        }
        String url = "jdbc:h2:file:" + database.toString().replace('\\', '/')
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0;IFEXISTS=TRUE";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            if (!connection.getMetaData().getDatabaseProductName().equals("H2")) throw new IllegalStateException("Require actual H2");
            String before = fingerprint(connection), mode = args[1];
            int changed = 0;
            if (!mode.equals("SNAPSHOT")) {
                String from = mode.equals("DEMOTE") ? "OPS_MANAGER" : "AUDITOR";
                String to = mode.equals("DEMOTE") ? "AUDITOR" : "OPS_MANAGER";
                try (var statement = connection.prepareStatement("UPDATE sys_user SET role_code=? WHERE id=3 AND username='lina' AND role_code=?")) {
                    statement.setString(1, to); statement.setString(2, from);
                    changed = statement.executeUpdate();
                    if (changed != 1) throw new IllegalStateException("Require exact seeded role transition");
                }
            }
            String role;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT role_code FROM sys_user WHERE id=3 AND username='lina'")) {
                if (!rows.next()) throw new IllegalStateException("Require seeded actor");
                role = rows.getString(1);
            }
            String after = fingerprint(connection);
            if (!before.equals(after)) throw new IllegalStateException("Role fixture must not change business records");
            System.out.println("{\"actualProduct\":\"H2\",\"actorId\":3,\"changed\":" + changed
                    + ",\"role\":\"" + role + "\",\"businessBefore\":\"" + before + "\",\"businessAfter\":\"" + after + "\"}");
        }
    }
    private static String fingerprint(Connection connection) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (String sql : java.util.List.of("SELECT * FROM oncall_open_handoff ORDER BY id",
                "SELECT * FROM oncall_open_handoff_operation ORDER BY handoff_id",
                "SELECT * FROM oncall_shift ORDER BY id", "SELECT * FROM oncall_handoff ORDER BY id",
                "SELECT * FROM oncall_shift_swap ORDER BY id", "SELECT * FROM audit_log WHERE action LIKE 'ONCALL_%' ORDER BY id")) {
            digest.update(sql.getBytes(StandardCharsets.UTF_8));
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                while (rows.next()) for (int index = 1; index <= rows.getMetaData().getColumnCount(); index++) {
                    String value = rows.getString(index);
                    digest.update((value == null ? "NULL" : value.length() + ":" + value).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
