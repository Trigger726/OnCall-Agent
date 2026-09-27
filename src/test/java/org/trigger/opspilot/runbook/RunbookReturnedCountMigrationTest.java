package org.trigger.opspilot.runbook;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class RunbookReturnedCountMigrationTest {
    @Test
    void shouldBackfillOnlyRecoverableActiveV28SnapshotsWithoutRewritingThem() throws Exception {
        String url = "jdbc:h2:mem:runbook-count-upgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("28").load().migrate();
        String[] json = {"[{\"stableKey\":\"one\"},{\"stableKey\":\"one\"},{\"stableKey\":\"two\"}]",
                "[]", "[]", "invalid-json", "[{\"stableKey\":12}]", "{}", "[{\"stableKey\":\" \"}]"};
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var insert = connection.prepareStatement("""
                     INSERT INTO runbook_retrieval_query(query_text, query_hash, source_type, requested_mode,
                       actual_engine, role_code, semantic_status, semantic_coverage, candidate_chunk_count,
                       top_k, latency_ms, results_json, snapshot_status)
                     VALUES ('LEGACY_QUERY', 'fixture', 'CONSOLE', 'BM25', 'BM25_LOCAL_V1',
                       'ADMIN', 'NOT_REQUESTED', 0, 3, 3, 1, ?, ?)
                     """)) {
            for (int i = 0; i < json.length; i++) {
                insert.setString(1, json[i]); insert.setString(2, i == 2 ? "PURGED" : "ACTIVE");
                insert.executeUpdate();
            }
            // Cross the migration's 500-row keyset boundary, including malformed rows before it.
            for (int i = 0; i < 501; i++) {
                insert.setString(1, "[{\"stableKey\":\"batch\"}]"); insert.setString(2, "ACTIVE");
                insert.executeUpdate();
            }
        }
        assertThat(Flyway.configure().dataSource(url, "sa", "").load().migrate().migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT returned_document_count, results_json, query_text "
                     + "FROM runbook_retrieval_query ORDER BY id")) {
            Integer[] counts = {2, 0, null, null, null, null, null};
            for (int i = 0; i < json.length; i++) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1, Integer.class)).isEqualTo(counts[i]);
                assertThat(rows.getString(2)).isEqualTo(json[i]);
                assertThat(rows.getString(3)).isEqualTo("LEGACY_QUERY");
            }
            for (int i = 0; i < 501; i++) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1, Integer.class)).isEqualTo(1);
            }
            assertThat(rows.next()).isFalse();
        }
    }
}
