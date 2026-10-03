package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Extend the immutable V32 request history without rewriting an applied migration. */
public class V33__assistant_queue_and_cancel extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        if (!"MySQL".equals(product) && !"H2".equals(product)) throw new IllegalStateException("Unsupported assistant request database");
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE assistant_request DROP " + ("MySQL".equals(product) ? "CHECK " : "CONSTRAINT ")
                    + "ck_assistant_request_status");
            statement.execute("ALTER TABLE assistant_request ADD CONSTRAINT ck_assistant_request_status "
                    + "CHECK(status IN ('QUEUED','RUNNING','COMPLETED','FAILED','TIMED_OUT','REVOKED','SUPERSEDED','CANCELLED'))");
        }
    }
}
