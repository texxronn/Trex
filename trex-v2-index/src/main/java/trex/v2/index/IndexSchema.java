package trex.v2.index;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Applies {@code schema.sql} (V2-PROPOSAL.md §7.2). Idempotent: every statement is IF NOT EXISTS. */
final class IndexSchema {

    private IndexSchema() {}

    static void apply(Connection conn) throws SQLException {
        String sql = read("/trex/v2/index/schema.sql");
        try (Statement st = conn.createStatement()) {
            // Old derived shapes: user_ack was period-keyed, and txn_current gained state_hash. The
            // derived tables are disposable, so drop/recreate rather than carry the old shape, and
            // clear the derived meta so the next apply re-derives into the new columns.
            if (hasColumn(st, "user_ack", "period")) {
                st.execute("DROP TABLE user_ack");
                clearDerivedMeta(st);
            }
            // txn_current gained a derived role (§6.9). The column is NOT NULL, so migration drops and
            // recreates the table (schema.sql carries the new shape) rather than adding a default; the
            // derived tables are disposable and the next apply re-derives with roles.
            if (hasTable(st, "txn_current") && !hasColumn(st, "txn_current", "role")) {
                st.execute("DROP TABLE txn_current");
                clearDerivedMeta(st);
            }
            // P6: txn_current gained the derived rail, transfer the payer method. Not nullable in
            // spirit, so drop/recreate rather than default; the next apply re-derives both.
            if (hasTable(st, "txn_current") && !hasColumn(st, "txn_current", "rail")) {
                st.execute("DROP TABLE txn_current");
                clearDerivedMeta(st);
            }
            if (hasTable(st, "transfer")
                && (!hasColumn(st, "transfer", "method") || !hasColumn(st, "transfer", "clearing_account"))) {
                st.execute("DROP TABLE transfer");
                clearDerivedMeta(st);
            }
            // txn_current gained the derived synthetic flag (clearing legs, §6.10). Derived and
            // disposable, so drop/recreate rather than ALTER; the next apply re-derives.
            if (hasTable(st, "txn_current") && !hasColumn(st, "txn_current", "synthetic")) {
                st.execute("DROP TABLE txn_current");
                clearDerivedMeta(st);
            }
            // The mirror shape changed (the uniform envelope): drop the mirrored fact/decision and
            // force a full re-mirror from the log. The mirror is disposable — the log is the truth.
            if (hasTable(st, "fact") && hasColumn(st, "fact", "ingested_at")) {
                st.execute("DROP TABLE fact");
                st.execute("DROP TABLE decision");
                st.execute("DROP TABLE IF EXISTS txn_current");
                st.execute("DELETE FROM meta");
            }
            for (String statement : statements(sql)) {
                st.execute(statement);
            }
            if (!hasColumn(st, "txn_current", "state_hash")) {
                st.execute("ALTER TABLE txn_current ADD COLUMN state_hash TEXT");
                clearDerivedMeta(st);
            }
            // commitment gained the candidate stem (V2-COMMITMENTS-PLAN.md §2.7; V2-EXPECTED-UX-PLAN.md
            // §7 Stage 2): nullable, so ALTER in place and force the next apply to re-derive into it.
            if (hasTable(st, "commitment") && !hasColumn(st, "commitment", "candidate_key")) {
                st.execute("ALTER TABLE commitment ADD COLUMN candidate_key TEXT");
                clearDerivedMeta(st);
            }
        }
    }

    /** Force the next apply to re-derive (used when a derived column's shape changes). */
    private static void clearDerivedMeta(Statement st) throws SQLException {
        if (hasTable(st, "meta")) {
            st.execute("DELETE FROM meta WHERE key IN ('derived_offset','as_of','config_revision')");
        }
    }

    private static boolean hasTable(Statement st, String table) throws SQLException {
        try (ResultSet rs = st.executeQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
            return rs.next();
        }
    }

    /** False when the table (or column) is missing; {@code PRAGMA table_info} is empty then. */
    private static boolean hasColumn(Statement st, String table, String column) throws SQLException {
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Strip comment-only lines, then split on ';'. Comments may contain semicolons, so splitting
     * first would execute prose — this is the bug that produced "syntax error near everything".
     */
    private static List<String> statements(String sql) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.strip().startsWith("--")) {
                withoutComments.append(line).append('\n');
            }
        }
        List<String> out = new ArrayList<>();
        for (String statement : withoutComments.toString().split(";")) {
            String trimmed = statement.strip();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static String read(String resource) {
        try (InputStream in = IndexSchema.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
