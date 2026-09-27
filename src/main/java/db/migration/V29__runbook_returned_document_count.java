package db.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.util.HashSet;
import java.util.ArrayList;
import java.sql.Connection;

/** Keep only non-sensitive cardinality when the original snapshot is later purged. */
public class V29__runbook_returned_document_count extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE runbook_retrieval_query ADD returned_document_count INT");
            statement.execute("ALTER TABLE runbook_retrieval_query ADD CONSTRAINT ck_runbook_returned_count "
                    + "CHECK (returned_document_count IS NULL OR returned_document_count BETWEEN 0 AND top_k)");
        }
        backfill(connection);
    }

    public void backfill(Connection connection) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // Already-purged [] is not evidence of an originally empty response.
        // Keyset batches bound memory, including drivers which buffer an entire ResultSet.
        try (var read = connection.prepareStatement("SELECT id, results_json, top_k FROM "
                + "runbook_retrieval_query WHERE id > ? AND snapshot_status = 'ACTIVE' "
                + "AND returned_document_count IS NULL ORDER BY id LIMIT 500");
             var update = connection.prepareStatement("UPDATE runbook_retrieval_query "
                     + "SET returned_document_count = ? WHERE id = ? AND returned_document_count IS NULL")) {
            long afterId = 0;
            while (true) {
                read.setLong(1, afterId);
                var batch = new ArrayList<Backfill>();
                try (var rows = read.executeQuery()) {
                    while (rows.next()) {
                        afterId = rows.getLong("id");
                        batch.add(new Backfill(afterId, documentCount(mapper,
                                rows.getString("results_json"), rows.getInt("top_k"))));
                    }
                }
                if (batch.isEmpty()) break;
                for (Backfill row : batch) {
                    if (row.count() == null) continue;
                    update.setInt(1, row.count());
                    update.setLong(2, row.id());
                    update.executeUpdate();
                }
            }
        }
    }

    private record Backfill(long id, Integer count) { }

    private Integer documentCount(ObjectMapper mapper, String json, int topK) {
        try {
            JsonNode results = mapper.readTree(json);
            if (results == null || !results.isArray() || results.size() > topK) return null;
            var keys = new HashSet<String>();
            for (JsonNode result : results) {
                JsonNode key = result.path("stableKey");
                if (!key.isTextual() || key.asText().isBlank()) return null;
                keys.add(key.asText());
            }
            return keys.size();
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidSnapshot) {
            // No raw snapshot or exception text in logs; malformed legacy data remains unknown.
            return null;
        }
    }
}
