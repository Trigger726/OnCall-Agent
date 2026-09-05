package org.trigger.opspilot.problem;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-problem-concurrency-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false"
})
class ProblemCreationConcurrencyIntegrationTest {
    private static final String FINGERPRINT = "c".repeat(64);
    private static final String RECURRENCE_KEY = "3:" + FINGERPRINT;

    @Autowired
    private ProblemService problemService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void shouldCreateOneProblemWhenSameCandidateIsPromotedConcurrently() throws Exception {
        seedRecurringIncidents();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var operation = (java.util.concurrent.Callable<ProblemService.ProblemCreateResult>) () -> {
                ready.countDown();
                start.await();
                return problemService.create(RECURRENCE_KEY,
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30),
                        3L, "127.0.0.1");
            };
            Future<ProblemService.ProblemCreateResult> first = executor.submit(operation);
            Future<ProblemService.ProblemCreateResult> second = executor.submit(operation);
            ready.await();
            start.countDown();

            List<ProblemService.ProblemCreateResult> results = List.of(first.get(), second.get());
            assertThat(results).extracting(ProblemService.ProblemCreateResult::created)
                    .containsExactlyInAnyOrder(true, false);
            assertThat(results.get(0).problem().id()).isEqualTo(results.get(1).problem().id());
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM problem_record")
                    .query(Long.class).single()).isEqualTo(1);
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM problem_incident_link")
                    .query(Long.class).single()).isEqualTo(2);
            assertThat(jdbcClient.sql("""
                            SELECT COUNT(*) FROM incident_timeline
                            WHERE event_type = 'PROBLEM_LINKED'
                            """).query(Long.class).single()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    private void seedRecurringIncidents() {
        jdbcClient.sql("""
                        INSERT INTO incident(
                          id, incident_code, title, severity, status, service_resource_id,
                          created_at, updated_at)
                        VALUES
                          (211, 'INC-CONCURRENT-211', '并发提升证据一', 'P2', 'OPEN', 3,
                           '2026-08-03 10:00:00', '2026-08-03 10:00:00'),
                          (212, 'INC-CONCURRENT-212', '并发提升证据二', 'P2', 'OPEN', 3,
                           '2026-08-13 10:00:00', '2026-08-13 10:00:00')
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO alert_event(
                          source, external_event_id, fingerprint, service_resource_id,
                          severity, status, title, description, labels_json,
                          first_occurred_at, last_occurred_at, occurrence_count, incident_id)
                        VALUES
                          ('prometheus', 'concurrent-211', :fingerprint, 3, 'P2', 'FIRING',
                           'Concurrent promotion signal', 'first', '{}',
                           '2026-08-03 10:00:00', '2026-08-03 10:00:00', 1, 211),
                          ('prometheus', 'concurrent-212', :fingerprint, 3, 'P2', 'FIRING',
                           'Concurrent promotion signal', 'second', '{}',
                           '2026-08-13 10:00:00', '2026-08-13 10:00:00', 1, 212)
                        """).param("fingerprint", FINGERPRINT).update();
    }
}
