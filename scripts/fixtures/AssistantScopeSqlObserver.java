import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Base64;

/** Acceptance-only observer, after owned JAR shutdown; IFEXISTS and read-only mode prevent creation/writes. */
public final class AssistantScopeSqlObserver {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || args[0].contains(";")) throw new IllegalArgumentException("Expected one H2 file base path");
        Class.forName("org.h2.Driver");
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + args[0].replace('\\', '/')
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "sa", "");
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id,query_text,candidate_chunk_count,results_json FROM runbook_retrieval_query WHERE source_type='ASSISTANT' ORDER BY id")) {
            while (rows.next()) System.out.println(rows.getLong(1) + "\t" + encode(rows.getString(2))
                    + "\t" + rows.getInt(3) + "\t" + encode(rows.getString(4)));
        }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
