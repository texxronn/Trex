package trex.egress.firefly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What has been projected, and as what. SPEC §5.8.
 * <p>
 * <b>An accelerator, not a record.</b> Deleting this file must cost eighteen requests and never a
 * fact, which is why every column here is recoverable from Firefly itself: the group id from
 * {@code external_id}, the last projected category from the {@code trex-category:} tag, and the
 * journal {@code n} from the notes. Nothing lives only here.
 * <p>
 * That decision buys the simplicity: no transactional cursor, no backup, no migration story. A
 * write lost to a crash is repaired by the next reconcile, and a schema change is served by
 * deleting the file.
 */
public final class ProjectionCache implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ProjectionCache.class);

    private static final String SCHEMA = """
        CREATE TABLE IF NOT EXISTS projected (
          external_id    TEXT PRIMARY KEY,
          n              INTEGER NOT NULL,
          group_id       TEXT NOT NULL,
          category       TEXT NOT NULL,
          rules_revision TEXT NOT NULL,
          projected_at   TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS egress_state (k TEXT PRIMARY KEY, v TEXT);
        """;

    /** What we last sent for one transaction. */
    public record Row(String externalId, long n, String groupId, String category, String rulesRevision) {}

    private final Connection db;
    private final boolean inMemory;

    /**
     * @param file where to keep it, or <b>null for memory only</b>. In-memory makes the
     *             "accelerator, not a record" claim literal rather than aspirational: there is no
     *             file to manage, to migrate, or to go stale, and the cache cannot disagree with
     *             Firefly because it is built from Firefly on every run. The cost is the rebuild —
     *             eighteen requests for this journal — which is the price of the claim being true.
     */
    public ProjectionCache(Path file) throws SQLException {
        this.inMemory = file == null;
        this.db = DriverManager.getConnection(inMemory ? "jdbc:sqlite::memory:" : "jdbc:sqlite:" + file);
        try (Statement st = db.createStatement()) {
            if (!inMemory) {
                // WAL for the same reason it mattered for Firefly: the rollback journal fsyncs a
                // file create and delete around every write, which is absurd for a cache.
                st.execute("PRAGMA journal_mode=WAL");
            }
            for (String ddl : SCHEMA.split(";")) {
                if (!ddl.isBlank()) {
                    st.execute(ddl);
                }
            }
        }
    }

    public Map<String, Row> all() throws SQLException {
        Map<String, Row> out = new LinkedHashMap<>();
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT external_id, n, group_id, category, rules_revision FROM projected")) {
            while (rs.next()) {
                out.put(rs.getString(1),
                    new Row(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)));
            }
        }
        return out;
    }

    public void record(Row row) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO projected (external_id, n, group_id, category, rules_revision, projected_at)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT(external_id) DO UPDATE SET
                  n = excluded.n, group_id = excluded.group_id,
                  category = excluded.category, rules_revision = excluded.rules_revision,
                  projected_at = excluded.projected_at
                """)) {
            ps.setString(1, row.externalId());
            ps.setLong(2, row.n());
            ps.setString(3, row.groupId());
            ps.setString(4, row.category());
            ps.setString(5, row.rulesRevision() == null ? "" : row.rulesRevision());
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    /** Replace the cache with what Firefly says — the rebuild, and the same path as `--verify`. */
    public void replaceAll(List<Row> rows) throws SQLException {
        boolean auto = db.getAutoCommit();
        db.setAutoCommit(false);
        try (Statement st = db.createStatement()) {
            st.execute("DELETE FROM projected");
            for (Row r : rows) {
                record(r);
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(auto);
        }
        log.info("cache rebuilt from Firefly: {} transactions", rows.size());
    }

    public boolean inMemory() {
        return inMemory;
    }

    /**
     * The highest journal n projected so far, so the next pass asks the gateway only for what
     * moved. Taken from the rows themselves rather than a stored counter: after a rebuild there is
     * no counter, and the rows carry the same answer — every one of them knows its own n, read
     * back from the notes Firefly holds.
     */
    public long highWater() throws SQLException {
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT COALESCE(MAX(n), 0) FROM projected WHERE n > 0")) {
            long fromRows = rs.next() ? rs.getLong(1) : 0;
            return Math.max(fromRows, storedHighWater());
        }
    }

    private long storedHighWater() throws SQLException {
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT v FROM egress_state WHERE k = 'highWaterN'")) {
            return rs.next() ? Long.parseLong(rs.getString(1)) : 0;
        }
    }

    public void highWater(long n) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO egress_state (k, v) VALUES ('highWaterN', ?) "
                    + "ON CONFLICT(k) DO UPDATE SET v = excluded.v")) {
            ps.setString(1, Long.toString(n));
            ps.executeUpdate();
        }
    }

    public String rulesRevision() throws SQLException {
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT v FROM egress_state WHERE k = 'rulesRevision'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    public void rulesRevision(String revision) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO egress_state (k, v) VALUES ('rulesRevision', ?) "
                    + "ON CONFLICT(k) DO UPDATE SET v = excluded.v")) {
            ps.setString(1, revision == null ? "" : revision);
            ps.executeUpdate();
        }
    }

    @Override
    public void close() {
        try {
            db.close();
        } catch (SQLException e) {
            log.warn("closing the cache", e);
        }
    }
}
