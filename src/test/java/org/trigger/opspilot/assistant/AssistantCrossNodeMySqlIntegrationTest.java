package org.trigger.opspilot.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MySQLContainer;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Owns a disposable database; the existing real-JAR HTTP runner still owns both independent JVMs. */
@EnabledIfSystemProperty(named = "opspilot.assistant.cross-node.mysql.enabled", matches = "true")
class AssistantCrossNodeMySqlIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void shouldVerifyNativeReplayRemoteCancelClearAndRevocationOnOwnedMySql() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path parent = root.resolve("target/assistant-cross-node-mysql-it");
        Path evidence = parent.resolve("audit-" + UUID.randomUUID());
        Files.createDirectories(evidence);
        String schema = "opspilot_cross_node_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName(schema).withUsername("opspilot").withPassword("test-password")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")) {
            mysql.start();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                System.out.println("CP83_CROSS_NODE_MYSQL_DATABASE " + JSON.writeValueAsString(identity(connection, schema)));
            }
            Path output = evidence.resolve("runner.log");
            var builder = new ProcessBuilder(System.getenv().getOrDefault("OPSPILOT_NODE_EXECUTABLE", "node"),
                    "scripts/verify-assistant-cross-node-ci.cjs").directory(root.toFile())
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("OPSPILOT_ASSISTANT_CROSS_NODE_MYSQL", "1");
            builder.environment().put("OPSPILOT_CROSS_NODE_JDBC_URL", mysql.getJdbcUrl());
            builder.environment().put("OPSPILOT_CROSS_NODE_DB_USER", mysql.getUsername());
            builder.environment().put("OPSPILOT_CROSS_NODE_DB_PASSWORD", mysql.getPassword());
            Process runner = builder.start();
            try {
                assertThat(runner.waitFor(3, TimeUnit.MINUTES)).as("bounded owned HTTP runner").isTrue();
                assertThat(runner.exitValue()).as("full HTTP runner; see %s", output).isZero();
            } finally {
                if (runner.isAlive()) {
                    runner.destroy();
                    if (!runner.waitFor(15, TimeUnit.SECONDS)) { runner.destroyForcibly(); runner.waitFor(5, TimeUnit.SECONDS); }
                }
            }
            List<String> results = Files.readString(output).lines().filter(line -> line.startsWith("{\"status\":")).toList();
            assertThat(results).hasSize(1);
            JsonNode node = JSON.readTree(results.get(0));
            assertThat(node.path("status").asText()).isEqualTo("PASS");
            assertThat(node.path("baselineCapture").asBoolean()).isFalse();
            assertThat(node.path("databaseMode").asText()).isEqualTo("MYSQL_TESTCONTAINER");
            assertThat(node.path("mysqlSchema").asText()).isEqualTo(schema);
            assertThat(node.path("cases").size()).isEqualTo(6);
            assertThat(node.path("startedPids").size()).isEqualTo(2);
            assertThat(node.path("startedPids").get(0).asLong()).isNotEqualTo(node.path("startedPids").get(1).asLong());
            for (JsonNode pid : node.path("startedPids")) {
                assertThat(ProcessHandle.of(pid.asLong()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            }
            for (String ports : List.of("applicationPorts", "managementPorts")) {
                for (JsonNode port : node.path(ports)) {
                    try (var socket = new ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1", port.asInt())); }
                }
            }
            Path run = root.resolve(node.path("evidenceDirectory").asText()).normalize();
            assertThat(run.startsWith(parent)).isTrue();
            List<Map<String, Object>> connections = new ArrayList<>();
            for (int number = 1; number <= 2; number++) {
                String log = Files.readString(run.resolve("jar-" + number + ".log"));
                String databaseLine = log.lines().filter(line -> line.contains("Database: " + mysql.getJdbcUrl().split("\\?")[0])
                        && line.contains("(MySQL 8.4)")).findFirst().orElseThrow();
                String start = "HikariPool-1 - Start completed.", stop = "HikariPool-1 - Shutdown completed.";
                assertThat(log.split(java.util.regex.Pattern.quote(start), -1)).hasSize(2);
                assertThat(log.split(java.util.regex.Pattern.quote(stop), -1)).hasSize(2);
                assertThat(log.indexOf(stop)).isGreaterThan(log.indexOf(start));
                connections.add(Map.of("node", number == 1 ? "A" : "B", "pid", node.path("startedPids").get(number - 1).asLong(),
                        "flywayDatabaseLine", databaseLine, "poolStartedAndStopped", true));
            }
            var audit = new LinkedHashMap<String, Object>();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                audit.put("database", identity(connection, schema));
                List<Map<String, Object>> facts = new ArrayList<>();
                for (JsonNode scenario : node.path("cases")) facts.add(sqlFact(connection, scenario));
                audit.put("sqlFacts", facts);
            }
            audit.put("status", "PASS"); audit.put("nodeConnections", connections);
            audit.put("runnerResultFile", root.relativize(run.resolve("result.json")).toString().replace('\\', '/'));
            audit.put("twoIndependentJvmsSameHost", true); audit.put("crossMachineOrDatabaseHaClaimed", false);
            audit.put("recordedJvmPidsVerifiedAbsent", true); audit.put("fourScopedPortsVerifiedFree", true);
            Files.writeString(evidence.resolve("audit.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(audit));
            System.out.println("CP83_CROSS_NODE_MYSQL_AUDIT " + root.relativize(evidence.resolve("audit.json")).toString().replace('\\', '/'));
        }
    }

    private static Map<String, Object> identity(Connection connection, String schema) throws Exception {
        var metadata = connection.getMetaData();
        assertThat(metadata.getDatabaseProductName()).isEqualTo("MySQL");
        assertThat(metadata.getDatabaseProductVersion()).startsWith("8.4.");
        assertThat(connection.getCatalog()).isEqualTo(schema);
        return Map.of("product", metadata.getDatabaseProductName(), "version", metadata.getDatabaseProductVersion(),
                "schema", connection.getCatalog());
    }

    private static Map<String, Object> sqlFact(Connection connection, JsonNode scenario) throws Exception {
        String name = scenario.path("name").asText(); long session = scenario.path("sessionId").asLong();
        assertThat(session).isPositive();
        var fact = new LinkedHashMap<String, Object>(); fact.put("name", name); fact.put("sessionId", session);
        String expected = switch (name) {
            case "shared-sql-completed-replay" -> "COMPLETED";
            case "cancel-on-B-releases-A" -> "CANCELLED";
            case "queued-cancel-on-B-reclaims-A", "queued-cancel-on-A-reclaims-B" -> "CANCELLED";
            case "clear-on-B-fences-A" -> "SUPERSEDED";
            case "revoke-on-B-fences-A" -> "REVOKED";
            default -> throw new AssertionError("Unexpected HTTP scenario: " + name);
        };
        try (var statement = connection.prepareStatement("SELECT status,question_message_id,answer_message_id FROM assistant_request WHERE session_id=?")) {
            statement.setLong(1, session);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                fact.put("status", rows.getString("status")); fact.put("answerMessageId", rows.getObject("answer_message_id", Long.class));
                fact.put("questionMessageId", rows.getObject("question_message_id", Long.class));
                assertThat(rows.getString("status")).isEqualTo(expected); assertThat(rows.next()).isFalse();
                fact.put("requestRows", 1);
            }
        }
        long userRows = count(connection, "SELECT COUNT(*) FROM assistant_message WHERE session_id=? AND role='USER'", session);
        long answerRows = count(connection, "SELECT COUNT(*) FROM assistant_message WHERE session_id=? AND role='ASSISTANT'", session);
        boolean queuedCancellation = name.startsWith("queued-cancel-on-");
        if (queuedCancellation) assertThat(fact.get("questionMessageId")).isNull();
        assertThat(userRows).isEqualTo("SUPERSEDED".equals(expected) || queuedCancellation ? 0 : 1);
        assertThat(answerRows).isEqualTo("COMPLETED".equals(expected) ? 1 : 0);
        fact.put("userRows", userRows); fact.put("answerRows", answerRows);
        if ("COMPLETED".equals(expected)) {
            assertThat(fact.get("answerMessageId")).isEqualTo(scenario.path("answerMessageId").asLong());
            try (var statement = connection.prepareStatement("SELECT content FROM assistant_message WHERE id=? AND session_id=?")) {
                statement.setLong(1, scenario.path("answerMessageId").asLong()); statement.setLong(2, session);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("  双节点原生事实🙂\n下一步验证。  ");
                    assertThat(rows.next()).isFalse(); fact.put("exactNativeUnicodeAnswerVerified", true);
                }
            }
        } else assertThat(fact.get("answerMessageId")).isNull();
        if ("CANCELLED".equals(expected)) {
            long audits = count(connection, "SELECT COUNT(*) FROM audit_log WHERE action='ASSISTANT_REQUEST_CANCEL' AND target_type='ASSISTANT_SESSION' AND target_id=?", session);
            assertThat(audits).isEqualTo(1); fact.put("cancellationAudits", audits);
        }
        if (queuedCancellation) {
            List<Map<String, Object>> controls = new ArrayList<>();
            for (String prefix : List.of("occupying", "replacement")) {
                long controlSession = scenario.path(prefix + "SessionId").asLong();
                long answer = scenario.path(prefix + "AnswerMessageId").asLong();
                assertThat(controlSession).isPositive(); assertThat(answer).isPositive();
                try (var statement = connection.prepareStatement("SELECT status,answer_message_id FROM assistant_request WHERE session_id=?")) {
                    statement.setLong(1, controlSession);
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue(); assertThat(rows.getString("status")).isEqualTo("COMPLETED");
                        assertThat(rows.getLong("answer_message_id")).isEqualTo(answer); assertThat(rows.next()).isFalse();
                    }
                }
                assertThat(count(connection, "SELECT COUNT(*) FROM assistant_message WHERE session_id=? AND role='USER'", controlSession)).isEqualTo(1);
                assertThat(count(connection, "SELECT COUNT(*) FROM assistant_message WHERE session_id=? AND role='ASSISTANT'", controlSession)).isEqualTo(1);
                try (var statement = connection.prepareStatement("SELECT content FROM assistant_message WHERE id=? AND session_id=?")) {
                    statement.setLong(1, answer); statement.setLong(2, controlSession);
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("  双节点原生事实🙂\n下一步验证。  ");
                        assertThat(rows.next()).isFalse();
                    }
                }
                controls.add(Map.of("role", prefix, "sessionId", controlSession, "status", "COMPLETED", "requestRows", 1,
                        "userRows", 1, "answerRows", 1, "answerMessageId", answer, "exactNativeUnicodeAnswerVerified", true));
            }
            fact.put("completedControls", controls);
        }
        return fact;
    }

    private static long count(Connection connection, String sql, long session) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, session);
            try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getLong(1); }
        }
    }
}
