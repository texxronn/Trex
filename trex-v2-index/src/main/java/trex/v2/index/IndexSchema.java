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
            for (String statement : statements(sql)) {
                st.execute(statement);
            }
            if (!hasColumn(st, "txn_current", "state_hash")) {
                st.execute("ALTER TABLE txn_current ADD COLUMN state_hash TEXT");
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
