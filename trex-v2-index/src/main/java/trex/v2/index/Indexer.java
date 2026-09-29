package trex.v2.index;

import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.Hashes;
import trex.v2.core.LogLine;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.derive.CategoryRow;
import trex.v2.core.derive.CurrentFact;
import trex.v2.core.derive.Derivation;
import trex.v2.core.derive.Derive;
import trex.v2.core.derive.IneffectiveDecision;
import trex.v2.core.derive.PendingRow;
import trex.v2.core.derive.ReviewItem;
import trex.v2.core.derive.Supersession;
import trex.v2.core.derive.TransferRow;
import trex.v2.core.derive.Unit;
import trex.v2.log.FramedReader;
import trex.v2.log.JournalCorruptException;
import trex.v2.log.LogCodec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The JDBC materializer (V2-PROPOSAL.md §7): the log mirrored row for row, then the derived tables
 * rebuilt by {@code derive()}. One writer connection; the file is private to the process that
 * holds the {@link IndexLock} and is rebuilt, never repaired.
 *
 * <p>On each apply the new log lines and the offset are written in one transaction (the
 * exactly-once trick), so power loss loses both or neither. The derived tables are then replaced
 * wholesale from {@code derive()} at the requested {@code asOf}; incremental refresh may come
 * later, but the contract that a full re-derive gives the same answer is what all of this rests on.
 */
public final class Indexer implements AutoCloseable {

    private final Connection conn;
    private DeriveConfig config;
    private final Path dbPath;

    private Indexer(Connection conn, DeriveConfig config, Path dbPath) {
        this.conn = conn;
        this.config = config;
        this.dbPath = dbPath;
    }

    public static Indexer open(Path dbPath, DeriveConfig config) {
        try {
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA foreign_keys=ON");
            }
            IndexSchema.apply(conn);
            return new Indexer(conn, config, dbPath);
        } catch (SQLException e) {
            throw new IndexException("cannot open index " + dbPath, e);
        }
    }

    /** Replace the derivation inputs; the next apply re-derives because {@code configRevision} moved. */
    public synchronized void setConfig(DeriveConfig config) {
        this.config = config;
    }

    public synchronized long offset() {
        return Long.parseLong(meta("log_offset").orElse("0"));
    }

    public synchronized Optional<String> meta(String key) {
        try (PreparedStatement ps = conn.prepareStatement(Sql.SELECT_META)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IndexException("cannot read meta." + key, e);
        }
    }

    /**
     * Apply any new log lines and re-derive if anything moved. Returns true when the derived state
     * was recomputed.
     */
    public synchronized boolean apply(Path journal, Instant asOf) {
        ReadResult read = readFrom(journal, offset());
        try {
            if (!read.lines().isEmpty()) {
                ingestMirror(read);
            }
            boolean upToDate = read.lines().isEmpty()
                && config.configRevision().equals(meta("config_revision").orElse(null))
                && asOf.toString().equals(meta("as_of").orElse(null))
                && String.valueOf(read.end()).equals(meta("derived_offset").orElse(null));
            if (upToDate) {
                return false;
            }
            deriveAndStore(asOf);
            return true;
        } catch (SQLException e) {
            throw new IndexException("apply failed against " + journal, e);
        }
    }

    /** Wipe every table and re-read the whole log (V2-PROPOSAL.md §4 rebuild test). */
    public synchronized void rebuild(Path journal, Instant asOf) {
        try {
            inTransaction(() -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("DELETE FROM fact");
                    st.execute("DELETE FROM decision");
                    for (String table : Sql.DERIVED_TABLES) {
                        st.execute(Sql.deleteAll(table));
                    }
                    st.execute("DELETE FROM meta");
                }
            });
        } catch (SQLException e) {
            throw new IndexException("cannot clear the index at " + dbPath, e);
        }
        apply(journal, asOf);
    }

    // ---- mirror -----------------------------------------------------------------------------

    private void ingestMirror(ReadResult read) throws SQLException {
        inTransaction(() -> {
            try (PreparedStatement f = conn.prepareStatement(Sql.INSERT_FACT);
                 PreparedStatement d = conn.prepareStatement(Sql.INSERT_DECISION);
                 PreparedStatement m = conn.prepareStatement(Sql.UPSERT_META)) {
                for (LogLine line : read.lines()) {
                    if (line instanceof Fact fact) {
                        insertFact(f, fact);
                    } else if (line instanceof Decision decision) {
                        insertDecision(d, decision);
                    }
                }
                m.setString(1, "log_offset");
                m.setString(2, String.valueOf(read.end()));
                m.executeUpdate();
            }
        });
    }

    private static void insertFact(PreparedStatement ps, Fact f) throws SQLException {
        ps.setLong(1, f.n());
        ps.setString(2, f.externalId());
        ps.setString(3, f.accountRef());
        ps.setString(4, f.date().toString());
        ps.setLong(5, f.amount());
        ps.setLong(6, f.balance());
        ps.setString(7, f.rawDescription());
        ps.setString(8, f.receipt());
        ps.setInt(9, f.occ());
        ps.setString(10, f.observation().wire());
        ps.setString(11, f.sourceType());
        ps.setString(12, f.provenance().wire());
        ps.setString(13, f.evidenceId());
        ps.setString(14, f.parser());
        ps.setString(15, f.ingestedAt().toString());
        ps.executeUpdate();
    }

    private static void insertDecision(PreparedStatement ps, Decision d) throws SQLException {
        ps.setLong(1, d.n());
        ps.setString(2, d.action().wire());
        ps.setString(3, LogCodec.encodeString(d));
        ps.setString(4, d.actor().wire());
        ps.setString(5, d.user());
        ps.setString(6, d.at().toString());
        ps.executeUpdate();
    }

    // ---- derivation -------------------------------------------------------------------------

    private void deriveAndStore(Instant asOf) throws SQLException {
        List<Fact> facts = readFacts();
        List<Decision> decisions = readDecisions();
        Derivation d = Derive.derive(facts, decisions, config, asOf);
        inTransaction(() -> {
            try (Statement st = conn.createStatement()) {
                for (String table : Sql.DERIVED_TABLES) {
                    st.execute(Sql.deleteAll(table));
                }
            }
            insertDerived(d);
            setMeta("config_revision", config.configRevision());
            setMeta("derive_version", DeriveConfig.DERIVE_VERSION);
            setMeta("hash_version", DeriveConfig.HASH_VERSION);
            setMeta("as_of", asOf.toString());
            setMeta("derived_offset", String.valueOf(offset()));
        });
    }

    private void insertDerived(Derivation d) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(Sql.INSERT_SUPERSESSION)) {
            for (Supersession s : d.supersession()) {
                ps.setString(1, s.fromId());
                ps.setString(2, s.toId());
                ps.setLong(3, s.decisionN());
                ps.setString(4, s.reason());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement(Sql.INSERT_CHAIN_RESOLVED)) {
            for (var e : d.chainResolved().entrySet()) {
                ps.setString(1, e.getKey());
                ps.setString(2, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement(Sql.INSERT_TXN_CURRENT)) {
            for (CurrentFact c : d.current()) {
                Fact f = c.fact();
                int i = 1;
                ps.setString(i++, c.externalId());
                ps.setLong(i++, f.n());
                ps.setString(i++, f.accountRef());
                ps.setString(i++, f.date().toString());
                ps.setLong(i++, f.amount());
                ps.setLong(i++, f.balance());
                ps.setString(i++, f.rawDescription());
                ps.setString(i++, f.receipt());
                ps.setInt(i++, f.occ());
                ps.setString(i++, f.observation().wire());
                ps.setString(i++, f.sourceType());
                ps.setString(i++, f.provenance().wire());
                ps.setString(i++, f.evidenceId());
                ps.setString(i++, f.parser());
                ps.setString(i++, f.ingestedAt().toString());
                ps.setString(i++, c.leg().name());
                ps.setString(i++, c.transferId());
                ps.setString(i++, c.category());
                ps.setString(i++, c.categoryOrigin().name());
                ps.setString(i++, c.ruleId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement(Sql.INSERT_TRANSFER)) {
            for (TransferRow t : d.transfers()) {
                ps.setString(1, t.transferId());
                ps.setString(2, t.fromLeg());
                ps.setString(3, t.toLeg());
                ps.setString(4, t.confidence().name());
                ps.setString(5, t.origin());
                if (t.decisionN() == null) {
                    ps.setNull(6, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(6, t.decisionN());
                }
                ps.setString(7, t.matchedAt().toString());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement(Sql.INSERT_PENDING)) {
            for (PendingRow p : d.pending()) {
                ps.setString(1, p.externalId());
                ps.setLong(2, p.fact().n());
                ps.setString(3, p.fact().accountRef());
                ps.setString(4, p.fact().date().toString());
                ps.setLong(5, p.fact().amount());
                ps.setString(6, p.settledBy());
                ps.setString(7, p.state().name());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement(Sql.INSERT_REVIEW_ITEM)) {
            for (ReviewItem r : d.review()) {
                ps.setString(1, r.subject());
                ps.setString(2, r.kind());
                ps.setString(3, r.detail());
                if (r.amountStake() == null) {
                    ps.setNull(4, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(4, r.amountStake());
                }
                ps.setString(5, r.openedAt().toString());
                ps.setString(6, r.stateHash());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement(Sql.INSERT_CATEGORY_CURRENT)) {
            for (CategoryRow c : d.categories()) {
                ps.setString(1, c.externalId());
                ps.setString(2, c.category());
                ps.setString(3, c.origin().name());
                ps.setString(4, c.ruleId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement(Sql.INSERT_PIN_CURRENT)) {
            for (var p : d.pins()) {
                ps.setString(1, p.externalId());
                ps.setString(2, p.category());
                ps.setLong(3, p.decisionN());
                ps.setString(4, p.userId());
                ps.setString(5, p.comment());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement(Sql.INSERT_INEFFECTIVE)) {
            for (IneffectiveDecision bad : d.ineffective()) {
                ps.setLong(1, bad.decisionN());
                ps.setString(2, bad.action());
                ps.setString(3, bad.reason());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement(Sql.INSERT_UNIT)) {
            for (Unit u : d.units()) {
                ps.setString(1, u.unitId());
                ps.setString(2, u.unitKind());
                ps.setString(3, u.accountRef());
                ps.setString(4, u.date().toString());
                ps.setLong(5, u.amount());
                ps.setString(6, u.currency());
                ps.setString(7, u.category());
                ps.setString(8, u.origin().name());
                ps.setString(9, u.pairing().name());
                ps.setBoolean(10, u.retired());
                ps.setBoolean(11, u.ineffective());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ---- mirror read ------------------------------------------------------------------------

    private List<Fact> readFacts() throws SQLException {
        List<Fact> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(Sql.SELECT_FACTS)) {
            while (rs.next()) {
                out.add(new Fact(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    java.time.LocalDate.parse(rs.getString(4)),
                    rs.getLong(5),
                    rs.getLong(6),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getInt(9),
                    Observation.fromWire(rs.getString(10)),
                    rs.getString(11),
                    Provenance.fromWire(rs.getString(12)),
                    rs.getString(13),
                    rs.getString(14),
                    Instant.parse(rs.getString(15))));
            }
        }
        return out;
    }

    private List<Decision> readDecisions() throws SQLException {
        List<Decision> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(Sql.SELECT_DECISIONS)) {
            while (rs.next()) {
                LogLine line = LogCodec.parse(rs.getString(1).getBytes(StandardCharsets.UTF_8));
                if (!(line instanceof Decision decision)) {
                    throw new IndexException("decision table holds a non-decision line n=" + line.n());
                }
                out.add(decision);
            }
        }
        return out;
    }

    // ---- verification support ---------------------------------------------------------------

    /** Row counts for the status strip (V2-PROPOSAL.md §14). */
    public synchronized java.util.Map<String, Long> counts() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        try (Statement st = conn.createStatement()) {
            for (String table : Sql.ALL_TABLES) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    out.put(table, rs.next() ? rs.getLong(1) : 0L);
                }
            }
        } catch (SQLException e) {
            throw new IndexException("cannot count the index", e);
        }
        return out;
    }

    /**
     * A hash of every derived table's rows, sorted, for the rebuild-equivalence test (§15.3) and
     * {@code trex verify}. Two indexes that derive the same answer share a fingerprint.
     */
    public synchronized String derivedFingerprint() {
        StringBuilder sb = new StringBuilder();
        try (Statement st = conn.createStatement()) {
            for (String table : Sql.DERIVED_TABLES) {
                List<String> rows = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("SELECT * FROM " + table)) {
                    int columns = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= columns; i++) {
                            Object v = rs.getObject(i);
                            row.append(v == null ? "\u0000" : v.toString()).append('\u0001');
                        }
                        rows.add(row.toString());
                    }
                }
                rows.sort(String::compareTo);
                sb.append(table).append('\u0002');
                rows.forEach(r -> sb.append(r).append('\u0003'));
            }
        } catch (SQLException e) {
            throw new IndexException("cannot fingerprint the index", e);
        }
        return Hashes.sha256(sb.toString());
    }

    // ---- helpers ----------------------------------------------------------------------------

    private interface SqlRunnable {
        void run() throws SQLException;
    }

    private void inTransaction(SqlRunnable body) throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            body.run();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    private void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(Sql.UPSERT_META)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private record ReadResult(List<LogLine> lines, long end) {}

    private static ReadResult readFrom(Path journal, long offset) {
        List<LogLine> lines = new ArrayList<>();
        long end = offset;
        try (FramedReader reader = new FramedReader(journal, offset)) {
            FramedReader.Framed f;
            while ((f = reader.next()) != null) {
                lines.add(f.line());
                end = f.endOffset();
            }
        } catch (JournalCorruptException e) {
            throw e;
        }
        return new ReadResult(lines, end);
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new IndexException("cannot close the index", e);
        }
    }
}
