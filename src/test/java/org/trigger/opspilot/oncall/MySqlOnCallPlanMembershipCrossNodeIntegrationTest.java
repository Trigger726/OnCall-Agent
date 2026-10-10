package org.trigger.opspilot.oncall;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MySQLContainer;
import java.nio.file.Path;
import java.util.UUID;

@EnabledIfSystemProperty(named="opspilot.oncall.membership.cross-node.mysql.enabled",matches="true")
class MySqlOnCallPlanMembershipCrossNodeIntegrationTest extends PlanMembershipCrossNodeScenarios {
    @Override OwnedDatabase database(Path run) {
        String schema="opspilot_member_cross_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        var mysql=new MySQLContainer<>("mysql:8.4").withDatabaseName(schema).withUsername("opspilot").withPassword(UUID.randomUUID().toString())
                .withUrlParam("connectionTimeZone","UTC").withUrlParam("forceConnectionTimeZoneToSession","true").withUrlParam("useSSL","false").withUrlParam("allowPublicKeyRetrieval","true")
                .withCommand("--character-set-server=utf8mb4","--collation-server=utf8mb4_unicode_ci","--default-time-zone=+00:00");
        mysql.start();return new OwnedDatabase(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword(),"MySQL",schema,()->{mysql.close();org.assertj.core.api.Assertions.assertThat(mysql.isRunning()).isFalse();});
    }
}
