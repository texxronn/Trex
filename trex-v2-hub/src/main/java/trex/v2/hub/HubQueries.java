package trex.v2.hub;

import trex.v2.hub.api.LedgerPage;
import trex.v2.hub.api.LedgerRow;
import trex.v2.hub.api.ReviewRow;
import trex.v2.hub.api.TransferJson;
import trex.v2.hub.api.ProjectionUnit;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The hub's read pool (V2-PROPOSAL.md §7.4): a few read-only connections so HTTP handlers never
 * contend with the single writer. WAL lets them read the previous snapshot while a batch commits;
 * {@code query_only} keeps them honest.
 */
public final class HubQueries implements AutoCloseable {

    private static final List<String> STATUS_TABLES = List.of(
        "fact", "decision", "ingest_event", "supersession", "chain_resolved", "txn_current", "transfer",
        "pending", "review_item", "category_current", "pin_current", "ineffective_decision", "unit", "evidence");

    private final BlockingQueue<Connection> pool;
    private final int size;

    public HubQueries(Path db, int size) {
        this.size = Math.max(1, size);
        this.pool = new ArrayBlockingQueue<>(this.size);
        try {
            for (int i = 0; i < this.size; i++) {
                Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA query_only=ON");
                    st.execute("PRAGMA busy_timeout=5000");
                }
                pool.add(conn);
            }
        } catch (SQLException e) {
            throw new HubException("cannot open the index read pool at " + db, e);
        }
    }

    /** A read closure; any SQLException is wrapped as a {@link HubException}. */
    public interface SqlFn<T> {
        T apply(Connection conn) throws SQLException;
    }

    public <T> T read(SqlFn<T> fn) {
        Connection conn;
        try {
            conn = pool.poll(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HubException("interrupted waiting for a read connection", e);
        }
        if (conn == null) {
            throw new HubException("no read connection available after 5s");
        }
        try {
            return fn.apply(conn);
        } catch (SQLException e) {
            throw new HubException("index read failed", e);
        } finally {
            pool.add(conn);
        }
    }

    public long offset() {
        return scalarLong("SELECT value FROM meta WHERE key = 'log_offset'", 0);
    }

    /** The highest {@code n} the index has mirrored. */
    public long logHeadN() {
        return scalarLong("SELECT COALESCE(MAX(n), 0) FROM (SELECT n FROM fact UNION ALL SELECT n FROM decision "
            + "UNION ALL SELECT n FROM ingest_event)", 0);
    }

    /** The status strip's counts (V2-PROPOSAL.md §14). */
    public Map<String, Long> counts() {
        return read(conn -> {
            Map<String, Long> out = new LinkedHashMap<>();
            try (Statement st = conn.createStatement()) {
                for (String table : STATUS_TABLES) {
                    try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        out.put(table, rs.next() ? rs.getLong(1) : 0L);
                    }
                }
            }
            return out;
        });
    }

    /**
     * The evidence ids that appear on facts — the derived "ingested" tick. Deliberately the log
     * (facts), not the evidence store: evidence is written before parsing, so a rejected file would
     * otherwise be marked done (V2-PROPOSAL.md §5.5, §12.5).
     */
    public java.util.Set<String> ingestedEvidenceIds() {
        return read(conn -> {
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT evidence_id FROM fact WHERE evidence_id IS NOT NULL")) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
            return ids;
        });
    }

    /** The ingest history, paired from the markers (V2-PROPOSAL.md §12.6), newest first. */
    public List<trex.v2.hub.api.IngestsResponse.IngestRow> ingests(int limit) {
        return read(conn -> {
            List<trex.v2.hub.api.IngestsResponse.IngestRow> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT batch, file, evidence_id, account_ref, n_start, n_end, appended, duplicate, flagged, "
                    + "status, started_ms, completed_ms FROM ingest_batch ORDER BY n_start DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new trex.v2.hub.api.IngestsResponse.IngestRow(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getLong(5), rs.getLong(6),
                            (Integer) rs.getObject(7), (Integer) rs.getObject(8), (Integer) rs.getObject(9),
                            rs.getString(10), rs.getLong(11), rs.getLong(12)));
                    }
                }
            }
            return out;
        });
    }

    // ---- accounts overview (V2-PROPOSAL.md §10.1, §10.5) ---------------------------------------

    /** All-time totals per account: first and last transaction date, and the count. */
    public Map<String, AccountTotals> accountTotals() {
        return read(conn -> {
            Map<String, AccountTotals> out = new LinkedHashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(HubSql.ACCOUNT_TOTALS)) {
                while (rs.next()) {
                    out.put(rs.getString(1), new AccountTotals(LocalDate.parse(rs.getString(2)),
                        LocalDate.parse(rs.getString(3)), rs.getLong(4)));
                }
            }
            return out;
        });
    }

    /** The current rows in a date window, each with the ingest file that owns it (nullable). */
    public List<CoverageRow> coverage(LocalDate from, LocalDate to) {
        return read(conn -> {
            List<CoverageRow> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(HubSql.ACCOUNT_COVERAGE)) {
                ps.setString(1, from.toString());
                ps.setString(2, to.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new CoverageRow(rs.getString(1), LocalDate.parse(rs.getString(2)),
                            rs.getString(3)));
                    }
                }
            }
            return out;
        });
    }

    /** Every ingest batch oldest-first; the caller keeps the newest per account. */
    public List<LastImport> lastImports() {
        return read(conn -> {
            List<LastImport> out = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(HubSql.ACCOUNT_LAST_IMPORTS)) {
                while (rs.next()) {
                    out.add(new LastImport(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4)));
                }
            }
            return out;
        });
    }

    /** Open review items by kind (open = derived and not dismissed). */
    public Map<String, Long> reviewByKind() {
        return read(conn -> {
            Map<String, Long> out = new LinkedHashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT kind, COUNT(*) FROM review_item GROUP BY kind ORDER BY kind")) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getLong(2));
                }
            }
            return out;
        });
    }

    // ---- blotter ----------------------------------------------------------------------------

    /** A filtered, sorted, paged page of current transactions (V2-PROPOSAL.md §10.2). */
    public LedgerPage ledger(BlotterQuery q) {
        return read(conn -> {
            List<Object> params = new ArrayList<>();
            String where = ledgerWhere(q, params);
            long total;
            try (PreparedStatement ps = conn.prepareStatement(HubSql.LEDGER_COUNT + where)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }
            String sortColumn = HubSql.LEDGER_SORT.get(q.sort());
            String direction = "asc".equals(q.order()) ? "ASC" : "DESC";
            String sql = HubSql.LEDGER_SELECT + where + " ORDER BY " + sortColumn + " " + direction
                + ", t.external_id ASC LIMIT ? OFFSET ?";
            List<LedgerRow> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int next = bind(ps, params);
                ps.setInt(next++, q.limit());
                ps.setInt(next, q.offset());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new LedgerRow(rs.getString(1), rs.getLong(2), rs.getString(3),
                            LocalDate.parse(rs.getString(4)), rs.getLong(5), rs.getLong(6), rs.getString(7),
                            rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                            rs.getString(12), rs.getInt(13) != 0));
                    }
                }
            }
            return new LedgerPage(total, rows);
        });
    }

    private static String ledgerWhere(BlotterQuery q, List<Object> params) {
        List<String> conditions = new ArrayList<>();
        if (q.account() != null) {
            conditions.add("t.account_ref = ?");
            params.add(q.account());
        }
        if (q.category() != null) {
            conditions.add("t.category = ?");
            params.add(q.category());
        }
        if (q.leg() != null) {
            conditions.add("t.leg = ?");
            params.add(q.leg());
        }
        if (q.direction() != null) {
            conditions.add("out".equals(q.direction()) ? "t.amount < 0" : "t.amount > 0");
        }
        if (q.from() != null) {
            conditions.add("t.date >= ?");
            params.add(q.from().toString());
        }
        if (q.to() != null) {
            conditions.add("t.date <= ?");
            params.add(q.to().toString());
        }
        if (q.q() != null) {
            conditions.add("LOWER(t.raw_description) LIKE ?");
            params.add("%" + q.q().toLowerCase(Locale.ROOT) + "%");
        }
        if (q.minAmount() != null) {
            conditions.add("ABS(t.amount) >= ?");
            params.add(q.minAmount());
        }
        if (q.maxAmount() != null) {
            conditions.add("ABS(t.amount) <= ?");
            params.add(q.maxAmount());
        }
        if (q.hasReview()) {
            conditions.add("EXISTS(SELECT 1 FROM review_item r WHERE r.subject = t.external_id)");
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    public List<ReviewRow> review(String kind) {
        return read(conn -> {
            String sql = HubSql.REVIEW_SELECT + (kind == null ? "" : " WHERE r.kind = ?") + HubSql.REVIEW_ORDER;
            List<ReviewRow> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (kind != null) {
                    ps.setString(1, kind);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long stake = rs.getLong(4);
                        String description = rs.getString(7);
                        rows.add(new ReviewRow(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.wasNull() ? null : stake, Instant.parse(rs.getString(5)), rs.getString(6),
                            description == null ? null : trex.v2.core.Clean.clean(description)));
                    }
                }
            }
            return rows;
        });
    }

    public List<TransferJson> transfers() {
        return read(conn -> {
            List<TransferJson> rows = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(HubSql.TRANSFERS_SELECT)) {
                while (rs.next()) {
                    long decisionN = rs.getLong(6);
                    rows.add(new TransferJson(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.wasNull() ? null : decisionN,
                        Instant.parse(rs.getString(7))));
                }
            }
            return rows;
        });
    }

    /** Projectable units, enriched for the egress (V2-PROPOSAL.md §11.2, §11.6). */
    public List<trex.v2.hub.api.ProjectionUnit> projectionUnits() {
        return read(conn -> {
            Map<String, trex.v2.core.Fact> facts = new java.util.TreeMap<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(HubSql.CURRENT_FACTS)) {
                while (rs.next()) {
                    trex.v2.core.Fact fact = new trex.v2.core.Fact(
                        new trex.v2.core.Envelope(rs.getLong(1), trex.v2.core.Fact.KIND, rs.getInt(15),
                            rs.getLong(16), rs.getString(17), rs.getString(18), rs.getString(19)),
                        rs.getString(2), rs.getString(3), LocalDate.parse(rs.getString(4)),
                        rs.getLong(5), rs.getLong(6), rs.getString(7), rs.getString(8), rs.getInt(9),
                        trex.v2.core.Observation.fromWire(rs.getString(10)), rs.getString(11),
                        trex.v2.core.Provenance.fromWire(rs.getString(12)), rs.getString(13), rs.getString(14));
                    facts.put(fact.externalId(), fact);
                }
            }
            Map<String, String[]> legs = new java.util.HashMap<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(HubSql.TRANSFER_LEGS)) {
                while (rs.next()) {
                    legs.put(rs.getString(1), new String[] { rs.getString(2), rs.getString(3) });
                }
            }
            List<trex.v2.hub.api.ProjectionUnit> out = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(HubSql.UNITS_SELECT)) {
                while (rs.next()) {
                    String unitId = rs.getString(1);
                    String kind = rs.getString(2);
                    String currency = rs.getString(6);
                    if ("TRANSFER".equals(kind)) {
                        String[] pair = legs.get(unitId);
                        trex.v2.core.Fact from = pair == null ? null : facts.get(pair[0]);
                        trex.v2.core.Fact to = pair == null ? null : facts.get(pair[1]);
                        if (from == null || to == null) {
                            continue;
                        }
                        out.add(new trex.v2.hub.api.ProjectionUnit(unitId, "TRANSFER",
                            Math.max(from.n(), to.n()), from.accountRef(), to.accountRef(), from.date(),
                            Math.abs(from.amount()), currency, "TRANSFER", "STRUCTURAL", "MATCHED",
                            false, false, from.rawDescription(),
                            unitHash(unitId, "TRANSFER", from.accountRef(), to.accountRef(), from.date(),
                                Math.abs(from.amount()), currency, "TRANSFER")));
                    } else {
                        trex.v2.core.Fact fact = facts.get(unitId);
                        if (fact == null) {
                            continue;
                        }
                        out.add(new trex.v2.hub.api.ProjectionUnit(unitId, "EXTERNAL", fact.n(),
                            fact.accountRef(), null, fact.date(), fact.amount(), currency, rs.getString(7),
                            rs.getString(8), rs.getString(9), rs.getInt(10) != 0, rs.getInt(11) != 0,
                            fact.rawDescription(),
                            unitHash(unitId, "EXTERNAL", fact.accountRef(), null, fact.date(), fact.amount(),
                                currency, rs.getString(7))));
                    }
                }
            }
            return out;
        });
    }

    /** The projectable units as core models, for state hashes. */
    public List<trex.v2.core.derive.Unit> unitModels() {
        return read(conn -> {
            List<trex.v2.core.derive.Unit> rows = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(HubSql.UNITS_SELECT)) {
                while (rs.next()) {
                    rows.add(new trex.v2.core.derive.Unit(rs.getString(1), rs.getString(2), rs.getString(3),
                        LocalDate.parse(rs.getString(4)), rs.getLong(5), rs.getString(6), rs.getString(7),
                        trex.v2.core.derive.CategoryOrigin.valueOf(rs.getString(8)),
                        trex.v2.core.derive.LegState.valueOf(rs.getString(9)),
                        rs.getInt(10) != 0, rs.getInt(11) != 0));
                }
            }
            return rows;
        });
    }

    /** The stored read markers, one row per (user, external_id). */
    public List<trex.v2.core.derive.UserAckRow> userAcks() {
        return read(conn -> {
            List<trex.v2.core.derive.UserAckRow> rows = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(HubSql.USER_ACK_SELECT)) {
                while (rs.next()) {
                    rows.add(new trex.v2.core.derive.UserAckRow(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        Instant.parse(rs.getString(7)), 0));
                }
            }
            return rows;
        });
    }

    /** The current row's content hash, or null when the id is not a current transaction. */
    public String rowStateHash(String externalId) {
        return read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(HubSql.ROW_STATE_HASH)) {
                ps.setString(1, externalId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /** Every current row's content hash, for read-marker staleness (V2-PROPOSAL.md §9.4). */
    public Map<String, String> currentStateHashes() {
        return read(conn -> {
            Map<String, String> out = new java.util.HashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(HubSql.CURRENT_STATE_HASHES)) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
            return out;
        });
    }

    /** The pending observations as the index holds them, for the eyeball walk (§10.3). */
    public List<PendingView> pending() {
        return read(conn -> {
            List<PendingView> rows = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(HubSql.PENDING_SELECT)) {
                while (rs.next()) {
                    rows.add(new PendingView(rs.getString(1), rs.getString(2), LocalDate.parse(rs.getString(3)),
                        rs.getLong(4), rs.getString(5), rs.getString(6)));
                }
            }
            return rows;
        });
    }

    /** The current posted facts (role {@code transaction}), for reconciliation and the walk. */
    public List<trex.v2.core.Fact> currentFacts() {
        return currentFacts(HubSql.CURRENT_FACTS);
    }

    /** The current {@code noop} facts, named as exclusions by the balance check (§6.9). */
    public List<trex.v2.core.Fact> currentNoops() {
        return currentFacts(HubSql.CURRENT_NOOPS);
    }

    private List<trex.v2.core.Fact> currentFacts(String sql) {
        return read(conn -> {
            List<trex.v2.core.Fact> rows = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    rows.add(new trex.v2.core.Fact(
                        new trex.v2.core.Envelope(rs.getLong(1), trex.v2.core.Fact.KIND, rs.getInt(15),
                            rs.getLong(16), rs.getString(17), rs.getString(18), rs.getString(19)),
                        rs.getString(2),
                        rs.getString(3),
                        LocalDate.parse(rs.getString(4)),
                        rs.getLong(5),
                        rs.getLong(6),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getInt(9),
                        trex.v2.core.Observation.fromWire(rs.getString(10)),
                        rs.getString(11),
                        trex.v2.core.Provenance.fromWire(rs.getString(12)),
                        rs.getString(13),
                        rs.getString(14)));
                }
            }
            return rows;
        });
    }

    /**
     * The latest effective role decision naming this row's chain, or null when none applies (§6.9).
     * The action is {@code MARK_NOOP} or {@code UNMARK_NOOP}; only the former classifies an
     * exclusion, the latter yields the profile default.
     */
    public RoleDecision roleDecision(String externalId) {
        return read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(HubSql.ROLE_DECISION)) {
                ps.setString(1, externalId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new RoleDecision(rs.getLong(1), rs.getString(2), rs.getString(3)) : null;
                }
            }
        });
    }

    /** The latest role decision for a row: its n, its action and a MARK_NOOP's reason. */
    public record RoleDecision(long n, String action, String reason) {}

    // ---- existence checks (decision precheck) ----------------------------------------------

    /** True when any fact id, superseded or not, resolves in the log. */
    public boolean factKnown(String externalId) {
        return exists(HubSql.FACT_KNOWN, externalId);
    }

    public boolean decisionKnown(long n) {
        return read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(HubSql.DECISION_KNOWN)) {
                ps.setLong(1, n);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /** The current leg state of an id, or null when it is not a current transaction. */
    public String legOf(String externalId) {
        return read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(HubSql.LEG_OF)) {
                ps.setString(1, externalId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private boolean exists(String sql, String value) {
        return read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, value);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    // ---- rows the accounts view reads ---------------------------------------------------------

    /** All-time first/last/count for one account. */
    record AccountTotals(LocalDate first, LocalDate last, long txns) {}

    /** One current row in the coverage window, with the ingest file that owns it (nullable). */
    record CoverageRow(String accountRef, LocalDate date, String file) {}

    /** One ingest batch as the coverage view needs it. */
    record LastImport(String accountRef, String file, String status, long startedMs) {}

    // ---- helpers ----------------------------------------------------------------------------

    private static int bind(PreparedStatement ps, List<Object> params) throws SQLException {
        int index = 1;
        for (Object value : params) {
            ps.setObject(index++, value);
        }
        return index;
    }

    /** The unit's projectable content, hashed so a content move (supersede, restatement) is visible. */
    private static String unitHash(String unitId, String kind, String accountRef, String toAccountRef,
                                   LocalDate date, long amount, String currency, String category) {
        return trex.v2.core.Hashes.sha256(String.join("|", unitId, kind,
            accountRef == null ? "" : accountRef, toAccountRef == null ? "" : toAccountRef,
            date.toString(), Long.toString(amount), currency == null ? "" : currency,
            category == null ? "" : category));
    }

    private long scalarLong(String sql, long fallback) {
        return read(conn -> {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : fallback;
            }
        });
    }

    public int size() {
        return size;
    }

    @Override
    public void close() {
        Connection conn;
        while ((conn = pool.poll()) != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                throw new HubException("cannot close a read connection", e);
            }
        }
    }
}
