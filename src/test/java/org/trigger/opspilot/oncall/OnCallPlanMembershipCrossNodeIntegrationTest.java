package org.trigger.opspilot.oncall;

import org.h2.tools.Server;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;

@EnabledIfSystemProperty(named="opspilot.oncall.membership.cross-node.h2.enabled",matches="true")
class OnCallPlanMembershipCrossNodeIntegrationTest extends PlanMembershipCrossNodeScenarios {
    @Override OwnedDatabase database(Path run) throws Exception {
        Path files=Files.createDirectory(run.resolve("database"));
        Server server=Server.createTcpServer("-tcpPort","0","-baseDir",files.toString(),"-ifNotExists").start();
        return new OwnedDatabase("jdbc:h2:tcp://127.0.0.1:"+server.getPort()+"/opspilot;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","","H2","owned-h2-tcp",()->{server.stop();org.assertj.core.api.Assertions.assertThat(server.isRunning(false)).isFalse();});
    }
}
