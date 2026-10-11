package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MySQLContainer;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Own MySQL, immutable populated V40 JAR, current enabled delivery, durable retry and human claim. */
@EnabledIfSystemProperty(named="opspilot.oncall.notification.upgrade.mysql.enabled",matches="true")
class MySqlOpenNotificationUpgradeHttpIntegrationTest {
    private static final ObjectMapper JSON=new ObjectMapper();

    @Test @Timeout(value=8,unit=TimeUnit.MINUTES)
    void shouldUpgradePopulatedV40AndDeliverStableNotificationAcrossJarRestarts() throws Exception {
        Path root=Path.of("").toAbsolutePath(),parent=root.resolve("target/oncall-open-notification-upgrade-mysql-it");
        Path evidence=parent.resolve("audit-"+UUID.randomUUID());Files.createDirectories(evidence);
        String schema="opspilot_notification_upgrade_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        var audit=new LinkedHashMap<String,Object>();
        var mysql=new MySQLContainer<>("mysql:8.4").withDatabaseName(schema).withUsername("opspilot").withPassword(UUID.randomUUID().toString())
                .withUrlParam("connectionTimeZone","UTC").withUrlParam("forceConnectionTimeZoneToSession","true")
                .withUrlParam("useSSL","false").withUrlParam("allowPublicKeyRetrieval","true")
                .withCommand("--character-set-server=utf8mb4","--collation-server=utf8mb4_unicode_ci","--default-time-zone=+00:00");
        try(mysql) {
            mysql.start();Map<String,Object> identity;
            try(var connection=DriverManager.getConnection(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());var statement=connection.createStatement()) {
                var metadata=connection.getMetaData();assertThat(metadata.getDatabaseProductName()).isEqualTo("MySQL");
                assertThat(metadata.getDatabaseProductVersion()).startsWith("8.4.");assertThat(connection.getCatalog()).isEqualTo(schema);
                try(var rows=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isZero();}
                try(var rows=statement.executeQuery("SELECT @@server_uuid")){assertThat(rows.next()).isTrue();identity=Map.of("product","MySQL","version",metadata.getDatabaseProductVersion(),"schema",schema,"serverUuid",rows.getString(1));}
            }
            audit.put("database",identity);System.out.println("OPEN_NOTIFICATION_UPGRADE_DATABASE "+JSON.writeValueAsString(identity));
            Path output=evidence.resolve("runner.log");
            var builder=new ProcessBuilder(System.getenv().getOrDefault("OPSPILOT_NODE_EXECUTABLE","node"),"scripts/verify-oncall-open-notification-http-ci.cjs")
                    .directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("OPSPILOT_NOTIFICATION_UPGRADE_MYSQL_OWNER","TESTCONTAINERS");
            builder.environment().put("OPSPILOT_UPGRADE_SCHEMA",schema);builder.environment().put("OPSPILOT_UPGRADE_JDBC_URL",mysql.getJdbcUrl());
            builder.environment().put("OPSPILOT_UPGRADE_DB_USER",mysql.getUsername());builder.environment().put("OPSPILOT_UPGRADE_DB_PASSWORD",mysql.getPassword());
            builder.environment().put("OPSPILOT_UPGRADE_SERVER_UUID",identity.get("serverUuid").toString());
            var runner=builder.start();
            try {assertThat(runner.waitFor(6,TimeUnit.MINUTES)).isTrue();assertThat(runner.exitValue()).as("actual seven JAR scenarios, see %s",output).isZero();}
            finally {if(runner.isAlive()){runner.destroy();if(!runner.waitFor(20,TimeUnit.SECONDS)){runner.descendants().forEach(ProcessHandle::destroyForcibly);runner.destroyForcibly();runner.waitFor(5,TimeUnit.SECONDS);}}}
            var lines=Files.readString(output).lines().filter(line->line.startsWith("{\"status\":")).toList();assertThat(lines).hasSize(1);
            var result=JSON.readTree(lines.get(0));assertThat(result.path("status").asText()).isEqualTo("PASS");
            assertThat(result.path("cases").asInt()).isEqualTo(7);assertThat(result.path("received").asInt()).isEqualTo(2);
            assertThat(result.path("startedPids").size()).isEqualTo(7);
            for(var pid:result.path("startedPids"))assertThat(ProcessHandle.of(pid.asLong()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            for(int port:List.of(9951,9952))try(var socket=new ServerSocket()){socket.bind(new InetSocketAddress("127.0.0.1",port));}
            Path run=root.resolve(result.path("evidenceDirectory").asText()).normalize();assertThat(run.startsWith(parent)).isTrue();
            Path resultFile=run.resolve("result.json");var full=JSON.readTree(Files.readString(resultFile));
            assertThat(full.path("databaseMode").asText()).isEqualTo("MYSQL_TESTCONTAINER");assertThat(full.path("mysqlSchema").asText()).isEqualTo(schema);
            audit.put("runnerResultFile",root.relativize(resultFile).toString().replace('\\','/'));
            try(var connection=DriverManager.getConnection(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());var statement=connection.createStatement()) {
                assertThat(connection.getCatalog()).isEqualTo(schema);
                try(var rows=statement.executeQuery("SELECT @@server_uuid")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo(identity.get("serverUuid"));}
                var counts=new LinkedHashMap<String,Long>();
                for(var entry:Map.ofEntries(
                        Map.entry("versionedMigrations","SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL"),
                        Map.entry("requests","SELECT COUNT(*) FROM oncall_open_handoff"),
                        Map.entry("humanClaims","SELECT COUNT(*) FROM oncall_open_handoff WHERE status='CLAIMED' AND claimed_by=1"),
                        Map.entry("operations","SELECT COUNT(*) FROM oncall_open_handoff_operation"),
                        Map.entry("publications","SELECT COUNT(*) FROM oncall_open_handoff_publication"),
                        Map.entry("memberOperations","SELECT COUNT(*) FROM oncall_schedule_member_operation"),
                        Map.entry("notifications","SELECT COUNT(*) FROM oncall_open_handoff_notification"),
                        Map.entry("delivered","SELECT COUNT(*) FROM oncall_open_handoff_notification WHERE status='DELIVERED'"),
                        Map.entry("skipped","SELECT COUNT(*) FROM oncall_open_handoff_notification WHERE status='SKIPPED'"),
                        Map.entry("attempts","SELECT SUM(attempts) FROM oncall_open_handoff_notification")).entrySet()) {
                    try(var rows=statement.executeQuery(entry.getValue())){assertThat(rows.next()).isTrue();counts.put(entry.getKey(),rows.getLong(1));}
                }
                assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("versionedMigrations",42L,"requests",4L,"humanClaims",1L,"operations",1L,"publications",4L,"memberOperations",5L,"notifications",2L,"delivered",1L,"skipped",1L,"attempts",3L));
                audit.put("finalJdbcCounts",counts);
            }
            audit.put("recordedJvmPidsVerifiedAbsent",true);audit.put("twoScopedPortsVerifiedFree",true);
        }
        assertThat(mysql.isRunning()).isFalse();audit.put("ownedContainerStopped",true);audit.put("status","PASS");
        Files.writeString(evidence.resolve("audit.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(audit));
        System.out.println("OPEN_NOTIFICATION_UPGRADE_AUDIT "+root.relativize(evidence.resolve("audit.json")).toString().replace('\\','/'));
        System.out.println("OPEN_NOTIFICATION_UPGRADE_CONTAINER_STOPPED {\"stopped\":true}");
    }
}
