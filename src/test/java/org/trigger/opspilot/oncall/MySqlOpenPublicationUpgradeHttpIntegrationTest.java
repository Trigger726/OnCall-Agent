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

/** Actual nonempty old/new JARs, real publication TCP, restart and independent JDBC on owned MySQL. */
@EnabledIfSystemProperty(named="opspilot.oncall.publication.upgrade.mysql.enabled",matches="true")
class MySqlOpenPublicationUpgradeHttpIntegrationTest {
    private static final ObjectMapper JSON=new ObjectMapper();

    @Test @Timeout(value=8,unit=TimeUnit.MINUTES)
    void shouldUpgradeNonemptyV39AndRetainFrozenPublicationAcrossHttpAndRestart() throws Exception {
        Path root=Path.of("").toAbsolutePath(),parent=root.resolve("target/oncall-open-publication-upgrade-mysql-it");
        Path evidence=parent.resolve("audit-"+UUID.randomUUID());Files.createDirectories(evidence);
        String schema="opspilot_publication_upgrade_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
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
            audit.put("database",identity);System.out.println("OPEN_PUBLICATION_UPGRADE_DATABASE "+JSON.writeValueAsString(identity));
            Path output=evidence.resolve("runner.log");
            var builder=new ProcessBuilder(System.getenv().getOrDefault("OPSPILOT_NODE_EXECUTABLE","node"),"scripts/verify-oncall-open-publication-upgrade-http-ci.cjs")
                    .directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("OPSPILOT_PUBLICATION_UPGRADE_MYSQL_OWNER","TESTCONTAINERS");
            builder.environment().put("OPSPILOT_UPGRADE_SCHEMA",schema);builder.environment().put("OPSPILOT_UPGRADE_JDBC_URL",mysql.getJdbcUrl());
            builder.environment().put("OPSPILOT_UPGRADE_DB_USER",mysql.getUsername());builder.environment().put("OPSPILOT_UPGRADE_DB_PASSWORD",mysql.getPassword());
            builder.environment().put("OPSPILOT_UPGRADE_SERVER_UUID",identity.get("serverUuid").toString());
            var runner=builder.start();
            try {assertThat(runner.waitFor(6,TimeUnit.MINUTES)).isTrue();assertThat(runner.exitValue()).as("actual old/new JAR runner, see %s",output).isZero();}
            finally {if(runner.isAlive()){runner.destroy();if(!runner.waitFor(20,TimeUnit.SECONDS)){runner.descendants().forEach(ProcessHandle::destroyForcibly);runner.destroyForcibly();runner.waitFor(5,TimeUnit.SECONDS);}}}
            var lines=Files.readString(output).lines().filter(line->line.startsWith("{\"status\":")).toList();assertThat(lines).hasSize(1);
            var result=JSON.readTree(lines.get(0));assertThat(result.path("status").asText()).isEqualTo("PASS");
            assertThat(result.path("databaseMode").asText()).isEqualTo("MYSQL_TESTCONTAINER");assertThat(result.path("mysqlSchema").asText()).isEqualTo(schema);
            assertThat(result.path("cases").size()).isEqualTo(6);assertThat(result.path("startedPids").size()).isEqualTo(4);
            for(var pid:result.path("startedPids"))assertThat(ProcessHandle.of(pid.asLong()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            for(int port:List.of(9961,9962))try(var socket=new ServerSocket()){socket.bind(new InetSocketAddress("127.0.0.1",port));}
            Path run=root.resolve(result.path("evidenceDirectory").asText()).normalize();assertThat(run.startsWith(parent)).isTrue();
            audit.put("runnerResultFile",root.relativize(run.resolve("result.json")).toString().replace('\\','/'));
            try(var connection=DriverManager.getConnection(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());var statement=connection.createStatement()) {
                assertThat(connection.getCatalog()).isEqualTo(schema);
                try(var rows=statement.executeQuery("SELECT @@server_uuid")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo(identity.get("serverUuid"));}
                var counts=new LinkedHashMap<String,Long>();
                for(var entry:Map.of("versionedMigrations","SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL",
                        "requests","SELECT COUNT(*) FROM oncall_open_handoff","withdrawn","SELECT COUNT(*) FROM oncall_open_handoff WHERE status='WITHDRAWN'",
                        "operations","SELECT COUNT(*) FROM oncall_open_handoff_operation","publications","SELECT COUNT(*) FROM oncall_open_handoff_publication",
                        "memberOperations","SELECT COUNT(*) FROM oncall_schedule_member_operation").entrySet()) {
                    try(var rows=statement.executeQuery(entry.getValue())){assertThat(rows.next()).isTrue();counts.put(entry.getKey(),rows.getLong(1));}
                }
                assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("versionedMigrations",40L,"requests",4L,"withdrawn",2L,"operations",2L,"publications",2L,"memberOperations",4L));
                audit.put("finalJdbcCounts",counts);
            }
            audit.put("recordedJvmPidsVerifiedAbsent",true);audit.put("twoScopedPortsVerifiedFree",true);
        }
        assertThat(mysql.isRunning()).isFalse();audit.put("ownedContainerStopped",true);audit.put("status","PASS");
        Files.writeString(evidence.resolve("audit.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(audit));
        System.out.println("OPEN_PUBLICATION_UPGRADE_AUDIT "+root.relativize(evidence.resolve("audit.json")).toString().replace('\\','/'));
        System.out.println("OPEN_PUBLICATION_UPGRADE_CONTAINER_STOPPED {\"stopped\":true}");
    }
}
