package org.trigger.opspilot.runbook;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-publication-race;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false"
})
class RunbookPublicationConcurrencyTest {
    @Autowired RunbookService books;
    @Autowired RunbookPublicationService reviews;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager manager;

    @Test void shouldSerializeTwoIndependentReviewerTransactions() throws Exception {
        PublicationScenarios.verifyConcurrent(books, reviews, jdbc);
    }
    @Test void shouldRollbackSupersedePublicationAndAuditTogether() {
        PublicationScenarios.verifyRollback(books, reviews, jdbc, manager);
    }
}
