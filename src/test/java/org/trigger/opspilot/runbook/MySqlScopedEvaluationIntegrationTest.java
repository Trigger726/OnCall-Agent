package org.trigger.opspilot.runbook;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

// Opt-in only: the harness supplies a fresh, isolated schema, never a developer's database.
@EnabledIfSystemProperty(named="opspilot.scoped.eval.mysql.enabled",matches="true")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties={
        "spring.datasource.url=${OPSPILOT_SCOPED_MYSQL_URL}",
        "spring.datasource.username=${OPSPILOT_SCOPED_MYSQL_USER}",
        "spring.datasource.password=${OPSPILOT_SCOPED_MYSQL_PASSWORD}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver", "spring.h2.console.enabled=false",
        "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false", "opspilot.runbook.semantic.enabled=false",
        "opspilot.oncall.rotation.enabled=false", "opspilot.oncall.escalation.enabled=false"
})
class MySqlScopedEvaluationIntegrationTest extends ScopedEvaluationScenarios {}
