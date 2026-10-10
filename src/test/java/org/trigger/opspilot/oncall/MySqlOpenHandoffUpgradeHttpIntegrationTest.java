package org.trigger.opspilot.oncall;

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

/** Actual old/new executable JAR upgrade, never a Flyway-only or H2 substitute. */
@EnabledIfSystemProperty(named="opspilot.oncall.open.upgrade.mysql.enabled",matches="true")
class MySqlOpenHandoffUpgradeHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test @Timeout(value=8,unit=TimeUnit.MINUTES)
    void shouldUpgradePopulatedV37AndRecoverOriginalClaimsAfterRestartOnOwnedMySql() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        Path parent=root.resolve("target/oncall-open-handoff-upgrade-mysql-it");
        Path evidence=parent.resolve("audit-"+UUID.randomUUID());
        Files.createDirectories(evidence);
        String schema="opspilot_open_upgrade_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        var audit=new LinkedHashMap<String,Object>();
        var mysql=new MySQLContainer<>("mysql:8.4").withDatabaseName(schema)
                .withUsername("opspilot").withPassword(UUID.randomUUID().toString())
                .withUrlParam("connectionTimeZone","UTC").withUrlParam("forceConnectionTimeZoneToSession","true")
                .withUrlParam("useSSL","false").withUrlParam("allowPublicKeyRetrieval","true")
                .withCommand("--character-set-server=utf8mb4","--collation-server=utf8mb4_unicode_ci","--default-time-zone=+00:00");
        try(mysql){
            mysql.start();
            String url=mysql.getJdbcUrl();
            Map<String,Object> identity;
            try(var connection=DriverManager.getConnection(url,mysql.getUsername(),mysql.getPassword())){
                identity=identity(connection,schema);
                // A freshly owned schema must be empty. No JDBC seeded imitation of old application writes.
                assertThat(count(connection,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")).isZero();
                // Exercise the fixture's exact-key semantics under the actual conflicting collations.
                var probe=new LinkedHashMap<String,Boolean>();
                for(var sample:Map.of("exactId","12","leadingZero","012","differentId","13","trailingSpace","12 ","numericAlias","1.2e1").entrySet()){
                    try(var statement=connection.prepareStatement("SELECT CAST(CONVERT(? USING utf8mb4) COLLATE utf8mb4_unicode_ci AS BINARY)=CAST(12 AS BINARY), CAST(CONVERT(? USING utf8mb4) COLLATE utf8mb4_unicode_ci AS BINARY)=CAST(CAST(12 AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci AS BINARY)")){
                        statement.setString(1,sample.getValue());
                        statement.setString(2,sample.getValue());
                        try(var rows=statement.executeQuery()){
                            assertThat(rows.next()).isTrue();boolean matches=rows.getBoolean(1);
                            assertThat(matches).as("exact audit key under different actual collations: %s",sample.getKey()).isEqualTo(sample.getKey().equals("exactId"));
                            assertThat(rows.getBoolean(2)).as("the same key across both actual CHAR collations").isEqualTo(matches);
                            probe.put(sample.getKey(),matches);
                        }
                    }
                }
                audit.put("binaryAuditKeyProbe",probe);
                System.out.println("CP105_OPEN_UPGRADE_BINARY_COMPARE "+JSON.writeValueAsString(probe));
            }
            audit.put("database",identity);
            System.out.println("CP104_OPEN_UPGRADE_DATABASE "+JSON.writeValueAsString(identity));
            Path output=evidence.resolve("runner.log");
            var builder=new ProcessBuilder(System.getenv().getOrDefault("OPSPILOT_NODE_EXECUTABLE","node"),
                    "scripts/verify-oncall-open-handoff-upgrade-mysql-ci.cjs").directory(root.toFile())
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("OPSPILOT_OPEN_UPGRADE_MYSQL_OWNER","TESTCONTAINERS");
            builder.environment().put("OPSPILOT_UPGRADE_SCHEMA",schema);
            builder.environment().put("OPSPILOT_UPGRADE_JDBC_URL",url);
            builder.environment().put("OPSPILOT_UPGRADE_DB_USER",mysql.getUsername());
            builder.environment().put("OPSPILOT_UPGRADE_DB_PASSWORD",mysql.getPassword());
            builder.environment().put("OPSPILOT_UPGRADE_SERVER_UUID",identity.get("serverUuid").toString());
            Process runner=builder.start();
            try{
                assertThat(runner.waitFor(6,TimeUnit.MINUTES)).as("bounded owned old/new HTTP runner").isTrue();
                assertThat(runner.exitValue()).as("full upgrade runner; see %s",output).isZero();
            }finally{
                if(runner.isAlive()){
                    runner.destroy();
                    if(!runner.waitFor(20,TimeUnit.SECONDS)){
                        runner.descendants().forEach(ProcessHandle::destroyForcibly);
                        runner.destroyForcibly();runner.waitFor(5,TimeUnit.SECONDS);
                    }
                }
            }
            List<String> lines=Files.readString(output).lines().filter(line->line.startsWith("{\"status\":")).toList();
            assertThat(lines).hasSize(1);
            JsonNode result=JSON.readTree(lines.get(0));
            assertThat(result.path("status").asText()).isEqualTo("PASS");
            assertThat(result.path("databaseMode").asText()).isEqualTo("MYSQL_TESTCONTAINER");
            assertThat(result.path("mysqlSchema").asText()).isEqualTo(schema);
            assertThat(result.path("oldSource").asText()).isEqualTo("a1262f6765b46416a7fdc192642cf631465f4caf");
            assertThat(result.path("cases").size()).isEqualTo(10);
            assertThat(result.path("startedPids").size()).isEqualTo(5);
            for(JsonNode pid:result.path("startedPids"))assertThat(ProcessHandle.of(pid.asLong()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            for(int port:List.of(9981,9982))try(var socket=new ServerSocket()){socket.bind(new InetSocketAddress("127.0.0.1",port));}
            Path run=root.resolve(result.path("evidenceDirectory").asText()).normalize();
            assertThat(run.startsWith(parent)).isTrue();
            var connections=new ArrayList<Map<String,Object>>();
            for(int number=1;number<=5;number++){
                String log=Files.readString(run.resolve("jar-"+number+".log"));
                String databaseLine=log.lines().filter(line->line.contains("Database: "+url.split("\\?")[0])&&line.contains("(MySQL 8.4)")).findFirst().orElseThrow();
                String start="HikariPool-1 - Start completed.",stop="HikariPool-1 - Shutdown completed.";
                assertThat(log.split(java.util.regex.Pattern.quote(start),-1)).hasSize(2);
                assertThat(log.split(java.util.regex.Pattern.quote(stop),-1)).hasSize(2);
                assertThat(log.indexOf(stop)).isGreaterThan(log.indexOf(start));
                connections.add(Map.of("jar",number,"pid",result.path("startedPids").get(number-1).asLong(),"flywayDatabaseLine",databaseLine,"poolStartedAndStopped",true));
            }
            try(var connection=DriverManager.getConnection(url,mysql.getUsername(),mysql.getPassword())){
                assertThat(identity(connection,schema)).isEqualTo(identity);
                assertThat(count(connection,"SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success=TRUE")).isEqualTo(40);
                assertThat(count(connection,"SELECT COUNT(*) FROM oncall_open_handoff")).isEqualTo(5);
                assertThat(count(connection,"SELECT COUNT(*) FROM oncall_open_handoff WHERE status='CLAIMED'")).isEqualTo(3);
                assertThat(count(connection,"SELECT COUNT(*) FROM oncall_open_handoff WHERE status='WITHDRAWN'")).isEqualTo(1);
                assertThat(count(connection,"SELECT COUNT(*) FROM oncall_open_handoff_operation")).isEqualTo(4);
                assertThat(count(connection,"SELECT COUNT(*) FROM oncall_swap_revocation")).isEqualTo(1);
                audit.put("finalJdbcCounts",Map.of("versionedMigrations",40,"openRequests",5,"claimed",3,"withdrawn",1,"open",1,"operations",4,"oldBilateralRevocations",1));
            }
            audit.put("nodeConnections",connections);
            audit.put("runnerResultFile",root.relativize(run.resolve("result.json")).toString().replace('\\','/'));
            audit.put("recordedJvmPidsVerifiedAbsent",true);audit.put("twoScopedPortsVerifiedFree",true);
        }
        assertThat(mysql.isRunning()).isFalse();
        audit.put("ownedContainerStopped",true);audit.put("status","PASS");
        Files.writeString(evidence.resolve("audit.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(audit));
        System.out.println("CP104_OPEN_UPGRADE_AUDIT "+root.relativize(evidence.resolve("audit.json")).toString().replace('\\','/'));
        System.out.println("CP104_OPEN_UPGRADE_CONTAINER_STOPPED {\"stopped\":true}");
    }

    private static Map<String,Object> identity(Connection connection,String schema) throws Exception {
        var metadata=connection.getMetaData();
        assertThat(metadata.getDatabaseProductName()).isEqualTo("MySQL");
        assertThat(metadata.getDatabaseProductVersion()).startsWith("8.4.");
        assertThat(connection.getCatalog()).isEqualTo(schema);
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT @@server_uuid")){
            assertThat(rows.next()).isTrue();
            return Map.of("product","MySQL","version",metadata.getDatabaseProductVersion(),"schema",schema,"serverUuid",rows.getString(1));
        }
    }
    private static long count(Connection connection,String query) throws Exception {
        try(var statement=connection.createStatement();var rows=statement.executeQuery(query)){assertThat(rows.next()).isTrue();return rows.getLong(1);}
    }
}
