package trex.v2.index;

import trex.v2.core.Decision;
import trex.v2.core.Envelope;
import trex.v2.core.Fact;
import trex.v2.core.IngestEvent;
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

    /**
     * Whether the journal's prefix below the persisted offset no longer matches what was indexed —
     * a <em>replaced</em> journal, not an appended one (V2-PROPOSAL.md §7.4). A shrink is already
     * caught by the offset; this catches a same-or-larger file materialised from different bytes.
     * The fingerprint is the last indexed line's bytes, so a replacement that reproduces that line
     * is indistinguishable from an append and is accepted (the accepted limit).
     */
    public synchronized boolean journalPrefixReplaced(Path journal) {
        long off = offset();
        Optional<String> hash = meta("log_tail_hash");
        Optional<String> len = meta("log_tail_len");
        if (off == 0 || hash.isEmpty() || len.isEmpty()) {
            return false;
        }
        int n;
        try {
            n = Integer.parseInt(len.get());
        } catch (NumberFormatException e) {
            return false;
        }
        if (n <= 0 || off < n) {
            return false;
        }
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(journal,
                java.nio.file.StandardOpenOption.READ)) {
            if (channel.size() < off) {
                return true;
            }
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(n);
            channel.position(off - n);
            while (buf.hasRemaining()) {
                if (channel.read(buf) < 0) {
                    return true;
                }
            }
            buf.flip();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return !Hashes.sha256(bytes).equals(hash.get());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("cannot verify the journal prefix", e);
        }
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
                    st.execute("DELETE FROM ingest_event");
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
                 PreparedStatement ie = conn.prepareStatement(Sql.INSERT_INGEST_EVENT);
                 PreparedStatement m = conn.prepareStatement(Sql.UPSERT_META)) {
                for (LogLine line : read.lines()) {
                    if (line instanceof Fact fact) {
                        insertFact(f, fact);
                    } else if (line instanceof Decision decision) {
                        insertDecision(d, decision);
                    } else if (line instanceof IngestEvent event) {
                        insertIngest(ie, event);
                    }
                }
                m.setString(1, "log_offset");
                m.setString(2, String.valueOf(read.end()));
                m.executeUpdate();
                if (read.tailBytes() != null) {
                    m.setString(1, "log_tail_hash");
                    m.setString(2, Hashes.sha256(read.tailBytes()));
                    m.executeUpdate();
                    m.setString(1, "log_tail_len");
                    m.setString(2, String.valueOf(read.tailBytes().length));
                    m.executeUpdate();
                }
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
        ps.setInt(15, f.envelope().v());
        ps.setLong(16, f.envelope().atMs());
        ps.setString(17, f.envelope().env());
        ps.setString(18, f.envelope().source());
        ps.setString(19, f.envelope().target());
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

    private static void insertIngest(PreparedStatement ps, IngestEvent e) throws SQLException {
        ps.setLong(1, e.n());
        ps.setString(2, e.phase());
        ps.setString(3, e.batch());
        ps.setString(4, e.evidence());
        ps.setString(5, e.file());
        ps.setString(6, e.accountRef());
        ps.setString(7, e.sourceType());
        ps.setString(8, e.parser());
        setNullableInt(ps, 9, e.appended());
        setNullableInt(ps, 10, e.duplicate());
        setNullableInt(ps, 11, e.flagged());
        ps.setString(12, e.status());
        ps.setInt(13, e.envelope().v());
        ps.setLong(14, e.envelope().atMs());
        ps.setString(15, e.envelope().env());
        ps.setString(16, e.envelope().source());
        ps.setString(17, e.envelope().target());
        ps.executeUpdate();
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
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
                ps.setInt(i++, f.envelope().v());
                ps.setLong(i++, f.envelope().atMs());
                ps.setString(i++, f.envelope().env());
                ps.setString(i++, f.envelope().source());
                ps.setString(i++, f.envelope().target());
                ps.setString(i++, c.role().wire());
                ps.setString(i++, c.leg().name());
                ps.setString(i++, c.transferId());
                ps.setString(i++, c.category());
                ps.setString(i++, c.categoryOrigin().name());
                ps.setString(i++, c.ruleId());
                ps.setString(i++, c.stateHash());
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
        try (PreparedStatement ps = conn.prepareStatement(Sql.INSERT_USER_ACK)) {
            for (var ack : d.userAcks()) {
                ps.setString(1, ack.userId());
                ps.setString(2, ack.externalId());
                ps.setString(3, ack.stateHash());
                ps.setString(4, ack.configRevision());
                ps.setString(5, ack.deriveVersion());
                ps.setString(6, ack.hashVersion());
                ps.setString(7, ack.ackedAt().toString());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ---- pure re-derivation for previews and diffs (does not touch the tables) ---------------

    /**
     * Run {@code derive()} against the mirror with a candidate config, restricted to lines with
     * {@code n <= maxN} (V2-PROPOSAL.md §9.3). Pure: it reads the mirror and returns tables,
     * writing nothing. Used by {@code reflow --preview} and the workbook.
     */
    public synchronized Derivation deriveWith(DeriveConfig candidate, Instant asOf, long maxN) {
        try {
            List<Fact> facts = readFacts(maxN);
            List<Decision> decisions = readDecisions(maxN);
            return Derive.derive(facts, decisions, candidate, asOf);
        } catch (SQLException e) {
            throw new IndexException("cannot re-derive from the mirror", e);
        }
    }

    // ---- projection state (V2-PROPOSAL.md §11.6) --------------------------------------------

    /** Every recorded projection-state row, for the egress's resume point. */
    public synchronized List<ProjectionRow> projection() {
        List<ProjectionRow> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(Sql.SELECT_PROJECTION)) {
            while (rs.next()) {
                out.add(new ProjectionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)));
            }
        } catch (SQLException e) {
            throw new IndexException("cannot read projection_state", e);
        }
        return out;
    }

    /** Record what was written to Firefly as each write lands; a rerun resumes rather than repeats. */
    public synchronized void upsertProjection(List<ProjectionRow> rows) {
        try {
            inTransaction(() -> {
                try (PreparedStatement ps = conn.prepareStatement(Sql.UPSERT_PROJECTION)) {
                    for (ProjectionRow row : rows) {
                        bindProjection(ps, row);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            });
        } catch (SQLException e) {
            throw new IndexException("cannot record projection_state", e);
        }
    }

    /** Rebuild the accelerator from Firefly ({@code --verify}): the same path a deleted cache takes. */
    public synchronized void replaceProjection(List<ProjectionRow> rows) {
        try {
            inTransaction(() -> {
                try (Statement st = conn.createStatement()) {
                    st.execute(Sql.DELETE_PROJECTION);
                }
                try (PreparedStatement ps = conn.prepareStatement(Sql.UPSERT_PROJECTION)) {
                    for (ProjectionRow row : rows) {
                        bindProjection(ps, row);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            });
        } catch (SQLException e) {
            throw new IndexException("cannot replace projection_state", e);
        }
    }

    private static void bindProjection(PreparedStatement ps, ProjectionRow row) throws SQLException {
        ps.setString(1, row.unitId());
        ps.setString(2, row.unitKind());
        ps.setString(3, row.groupId());
        ps.setString(4, row.category());
        ps.setString(5, row.stateHash());
        ps.setString(6, row.configRevision());
        ps.setString(7, row.deriveVersion());
        ps.setString(8, row.verifiedAt());
    }

    // ---- evidence and source cursors (V2-PROPOSAL.md §7.2, §12.2) ---------------------------

    /** Record evidence-store entries; existing rows are left alone, so {@code first_seen} is stable. */
    public synchronized void upsertEvidence(List<EvidenceRow> rows) {
        try {
            inTransaction(() -> {
                try (PreparedStatement ps = conn.prepareStatement(Sql.UPSERT_EVIDENCE)) {
                    for (EvidenceRow row : rows) {
                        ps.setString(1, row.sha256());
                        ps.setString(2, row.path());
                        ps.setLong(3, row.bytes());
                        ps.setString(4, row.mediaType());
                        ps.setString(5, row.sourceType());
                        ps.setString(6, row.firstSeen());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            });
        } catch (SQLException e) {
            throw new IndexException("cannot record evidence", e);
        }
    }

    public synchronized java.util.Map<String, String> cursors() {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(Sql.SELECT_CURSORS)) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            throw new IndexException("cannot read source_cursor", e);
        }
        return out;
    }

    public synchronized void putCursor(String source, String cursor, String at) {
        try {
            inTransaction(() -> {
                try (PreparedStatement ps = conn.prepareStatement(Sql.UPSERT_CURSOR)) {
                    ps.setString(1, source);
                    ps.setString(2, cursor);
                    ps.setString(3, at);
                    ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            throw new IndexException("cannot record source cursor", e);
        }
    }

    // ---- mirror read ------------------------------------------------------------------------

    private List<Fact> readFacts() throws SQLException {
        return readFacts(Long.MAX_VALUE);
    }

    private List<Fact> readFacts(long maxN) throws SQLException {
        List<Fact> out = new ArrayList<>();
        String sql = maxN == Long.MAX_VALUE ? Sql.SELECT_FACTS : Sql.SELECT_FACTS_UPTO;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (maxN != Long.MAX_VALUE) {
                ps.setLong(1, maxN);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Fact(
                        new Envelope(rs.getLong(1), Fact.KIND, rs.getInt(15), rs.getLong(16),
                            rs.getString(17), rs.getString(18), rs.getString(19)),
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
                        rs.getString(14)));
                }
            }
        }
        return out;
    }

    private List<Decision> readDecisions() throws SQLException {
        return readDecisions(Long.MAX_VALUE);
    }

    private List<Decision> readDecisions(long maxN) throws SQLException {
        List<Decision> out = new ArrayList<>();
        String sql = maxN == Long.MAX_VALUE ? Sql.SELECT_DECISIONS : Sql.SELECT_DECISIONS_UPTO;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (maxN != Long.MAX_VALUE) {
                ps.setLong(1, maxN);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LogLine line = LogCodec.parse(rs.getString(1).getBytes(StandardCharsets.UTF_8));
                    if (!(line instanceof Decision decision)) {
                        throw new IndexException("decision table holds a non-decision line n=" + line.n());
                    }
                    out.add(decision);
                }
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

    /** Every current fact, whichever role (V2-PROPOSAL.md §7.2); for identity checks. */
    public synchronized List<Fact> currentFacts() {
        return currentFacts("SELECT n, external_id, account_ref, date, amount, balance, raw_description, receipt, "
            + "occ, observation, source_type, provenance, evidence_id, parser, line_v, at_ms, env, source, target "
            + "FROM txn_current ORDER BY n");
    }

    /** The current posted facts (role {@code transaction}), for reconciliation (V2-PROPOSAL.md §15.10). */
    public synchronized List<Fact> currentTransactions() {
        return currentFacts("SELECT n, external_id, account_ref, date, amount, balance, raw_description, receipt, "
            + "occ, observation, source_type, provenance, evidence_id, parser, line_v, at_ms, env, source, target "
            + "FROM txn_current WHERE role = 'transaction' ORDER BY n");
    }

    /** The current {@code noop} facts, named as exclusions by the balance check (§6.9). */
    public synchronized List<Fact> currentNoops() {
        return currentFacts("SELECT n, external_id, account_ref, date, amount, balance, raw_description, receipt, "
            + "occ, observation, source_type, provenance, evidence_id, parser, line_v, at_ms, env, source, target "
            + "FROM txn_current WHERE role = 'noop' ORDER BY n");
    }

    private List<Fact> currentFacts(String sql) {
        List<Fact> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new Fact(
                    new Envelope(rs.getLong(1), Fact.KIND, rs.getInt(15), rs.getLong(16),
                        rs.getString(17), rs.getString(18), rs.getString(19)),
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
                    rs.getString(14)));
            }
        } catch (SQLException e) {
            throw new IndexException("cannot read txn_current", e);
        }
        return out;
    }

    /** The highest {@code n} the index has mirrored (for the change feed). */
    public synchronized long logHeadN() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT COALESCE(MAX(n), 0) FROM (SELECT n FROM fact UNION ALL SELECT n FROM decision "
                     + "UNION ALL SELECT n FROM ingest_event)")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IndexException("cannot read the log head from the index", e);
        }
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

    private record ReadResult(List<LogLine> lines, long end, byte[] tailBytes) {}

    private static ReadResult readFrom(Path journal, long offset) {
        List<LogLine> lines = new ArrayList<>();
        long end = offset;
        byte[] tail = null;
        try (FramedReader reader = new FramedReader(journal, offset)) {
            FramedReader.Framed f;
            while ((f = reader.next()) != null) {
                lines.add(f.line());
                end = f.endOffset();
                // Keep the trailing '\n' so the fingerprint is exactly [end - len, end).
                byte[] raw = f.bytes();
                tail = java.util.Arrays.copyOf(raw, raw.length + 1);
                tail[raw.length] = '\n';
            }
        } catch (JournalCorruptException e) {
            throw e;
        }
        return new ReadResult(lines, end, tail);
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
