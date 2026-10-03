package org.trigger.opspilot.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.trigger.opspilot.security.SessionAuthorization;

import javax.sql.DataSource;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-assistant-transactions;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "management.server.port=0", "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false"
})
class AssistantTransactionIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired AssistantService assistant;
    @Autowired SessionAuthorization authorization;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;

    AssistantTransactionScenarios scenario() {
        return new AssistantTransactionScenarios(jdbc, assistant, authorization, transactions, dataSource);
    }
    @Test void shouldRollbackAssistantAnswerWhenCompletionAuditInsertFails() throws Exception {
        try (var s = scenario()) { s.auditInsertFailureRollsBackAnswerAndTitle(); }
    }
    @Test void shouldRollbackAssistantCompletionWhenFinalStopCheckFails() {
        try (var s = scenario()) { s.finalStopRollsBackAnswerTitleAndAlreadyInsertedAudit(); }
    }
    @Test void shouldFenceAssistantFinalCommitAgainstConcurrentRevocation() throws Exception {
        try (var s = scenario()) { s.finalAccountLockWaitSeesCommittedRevocation(); }
    }
    @Test void shouldRecheckAssistantLeaseExpiryAfterFinalLockWait() throws Exception {
        try (var s = scenario()) { s.finalAccountLockWaitRechecksExpiry(); }
    }
    @Test void shouldRollbackAssistantCompletionWhenLeaseExpiresDuringPersistence() {
        try (var s = scenario()) { s.expiryDuringCompletionRollsBackAnswerTitleAndAudit(); }
    }
    @Test void shouldReplayCompletedAssistantKeyWithCaseSensitiveSessionScope() {
        try (var s = scenario()) { s.completedKeyBindsQuestionAndPreservesCaseSensitiveSessionScope(); }
    }
    @Test void shouldRollbackKeyedAssistantCompletionStateWithAnswerAndAudit() {
        try (var s = scenario()) { s.keyedCompletionRollsBackStateAnswerTitleAndAuditTogether(); }
    }
    @Test void shouldFenceConcurrentAssistantKeyAndPreserveOriginalAttempt() throws Exception {
        try (var s = scenario()) { s.concurrentKeyNeverCreatesSecondQuestionOrStopsOriginalAttempt(); }
    }
}
