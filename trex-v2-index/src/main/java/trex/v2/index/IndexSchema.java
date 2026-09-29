package trex.v2.index;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
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
            for (String statement : statements(sql)) {
                st.execute(statement);
            }
        }
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
