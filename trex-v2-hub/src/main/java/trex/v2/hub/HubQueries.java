package trex.v2.hub;

import trex.v2.hub.api.ActivityJson;
import trex.v2.hub.api.BudgetResponse;
import trex.v2.hub.api.CashMonthJson;
import trex.v2.hub.api.CommitmentJson;
import trex.v2.hub.api.DismissalJson;
import trex.v2.hub.api.ExpectedResponse;
import trex.v2.hub.api.LedgerPage;
import trex.v2.hub.api.LedgerRow;
import trex.v2.hub.api.NoteJson;
import trex.v2.hub.api.ReviewMember;
import trex.v2.hub.api.ReviewRow;
import trex.v2.hub.api.SinceResponse;
import trex.v2.hub.api.StatusResponse;
import trex.v2.hub.api.TransferJson;
import trex.v2.hub.api.ProjectionUnit;
import trex.v2.core.MerchantStem;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.TransferRules;
import trex.v2.core.derive.CommitmentRule;
import trex.v2.core.derive.CommitmentRules;
import trex.v2.core.derive.Commitments;
import trex.v2.core.derive.Period;
import trex.v2.core.derive.ReviewItem;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
        "pending", "review_item", "category_current", "pin_current", "ineffective_decision",
        "commitment", "commitment_rule", "commitment_occurrence", "commitment_note",
        "commitment_exclusion", "unit", "evidence");

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

    /**
     * The ingest history, paired from the markers (V2-PROPOSAL.md §12.6), newest first. Null
     * returns the newest {@code limit} batches, including one whose complete marker has not
     * arrived — the Jobs page. {@code sinceN} is the catch-up the ingest toast sends on an SSE
     * delta (V2-QOL-IMPROVEMENTS-PLAN.md §1): every batch that completed after it ({@code n_end > sinceN}),
     * never truncated to the page size, because a delta that missed the older half of its range
     * could never report it again.
     */
    public List<trex.v2.hub.api.IngestsResponse.IngestRow> ingests(int limit, Long sinceN) {
        LocalDate asOf = LocalDate.now();
        return read(conn -> {
            List<trex.v2.hub.api.IngestsResponse.IngestRow> out = new ArrayList<>();
            String sql = "SELECT batch, file, evidence_id, account_ref, "
                // The view pairs the markers but predates source_type; the start row carries it.
                + "(SELECT s.source_type FROM ingest_event s WHERE s.batch = ingest_batch.batch "
                + "AND s.phase = 'start'), "
                + "n_start, n_end, appended, duplicate, "
                + "flagged, status, started_ms, completed_ms, "
                + "(SELECT MAX(t.date) FROM txn_current t WHERE t.account_ref = ingest_batch.account_ref "
                + "AND t.date <= ?) FROM ingest_batch"
                + (sinceN == null ? "" : " WHERE n_end > ?")
                + " ORDER BY n_start DESC"
                + (sinceN == null ? " LIMIT ?" : "");
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, asOf.toString());
                if (sinceN == null) {
                    ps.setInt(2, limit);
                } else {
                    ps.setLong(2, sinceN);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new trex.v2.hub.api.IngestsResponse.IngestRow(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getLong(6), rs.getLong(7),
                            (Integer) rs.getObject(8), (Integer) rs.getObject(9), (Integer) rs.getObject(10),
                            rs.getString(11), rs.getLong(12), rs.getLong(13),
                            nullableDate(rs.getString(14))));
                    }
                }
            }
            return out;
        });
    }

    // ---- since you last cleared (V2-QOL-IMPROVEMENTS-PLAN.md §5) -----------------------------

    /** The since-clear read's raw result; the wire shape adds the month's headroom. */
    public record Since(Instant at, long rows, List<SinceResponse.AccountCount> accounts, long batches,
                        long items, List<SinceResponse.Occurrence> occurrences,
                        List<SinceResponse.UserCount> decisions) {}

    /**
     * What the log appended after line {@code n} (V2-QOL-IMPROVEMENTS-PLAN.md §5): facts (total
     * and per account), completed ingest batches, review items opened after the line's time,
     * occurrences that turned {@code occurred}/{@code missed}, and the decisions after {@code n}
     * that are not {@code user}'s own ({@code user} null keeps every decision). Pure reads; the
     * marker is a browser thing and never reaches here as state.
     *
     * <p>The marker's time is the appended time of line {@code n} — {@code fact.at_ms} or
     * {@code ingest_event.at_ms} as epoch millis, {@code decision.at} as an instant. A line that
     * is not in the log ({@code n <= 0}, past the head, or a rebuilt journal that no longer has
     * it) is not an error: the result has a null {@code at} and zeros. A {@code missed}
     * occurrence counts when its window closed after the marker's <em>UTC</em> date, the calendar
     * the derive itself measures windows in.
     */
    public Since since(long n, String user) {
        return read(conn -> {
            Instant at = markerInstant(conn, n);
            if (at == null) {
                return new Since(null, 0, List.of(), 0, 0, List.of(), List.of());
            }
            List<SinceResponse.AccountCount> accounts = new ArrayList<>();
            long rows = 0;
            try (PreparedStatement ps = conn.prepareStatement(HubSql.SINCE_FACTS)) {
                ps.setLong(1, n);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long count = rs.getLong(2);
                        rows += count;
                        accounts.add(new SinceResponse.AccountCount(rs.getString(1), count));
                    }
                }
            }
            long batches = count(conn, HubSql.SINCE_BATCHES, n);
            long items = count(conn, HubSql.SINCE_ITEMS, at.toString());
            List<SinceResponse.Occurrence> occurrences = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(HubSql.SINCE_OCCURRENCES)) {
                ps.setLong(1, n);
                ps.setString(2, at.atZone(java.time.ZoneOffset.UTC).toLocalDate().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        occurrences.add(new SinceResponse.Occurrence(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), LocalDate.parse(rs.getString(5)),
                            nullableDate(rs.getString(6))));
                    }
                }
            }
            List<SinceResponse.UserCount> decisions = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(HubSql.SINCE_DECISIONS)) {
                ps.setLong(1, n);
                ps.setString(2, user);
                ps.setString(3, user);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        decisions.add(new SinceResponse.UserCount(rs.getString(1), rs.getLong(2)));
                    }
                }
            }
            return new Since(at, rows, accounts, batches, items, occurrences, decisions);
        });
    }

    /** The appended time of log line {@code n}, or null when no level-1 table holds that line. */
    private static Instant markerInstant(Connection conn, long n) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(HubSql.SINCE_MARKER)) {
            ps.setLong(1, n);
            ps.setLong(2, n);
            ps.setLong(3, n);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long atMs = rs.getLong(1);
                if (!rs.wasNull()) {
                    return Instant.ofEpochMilli(atMs);
                }
                atMs = rs.getLong(2);
                if (!rs.wasNull()) {
                    return Instant.ofEpochMilli(atMs);
                }
                String at = rs.getString(3);
                return at == null ? null : Instant.parse(at);
            }
        }
    }

    /** One scalar count on an already-open read connection. */
    private static long count(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
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

    /**
     * The expectations nudge (V2-SPEC.md §9.1): how many declared commitments have an occurrence
     * with an open window due inside {@code week}. Occurrences, not commitments or dollars. A read
     * of the disposable index; no state.
     */
    public long expectedDueThisWeek(Period.Range week) {
        return read(conn -> count(conn, HubSql.EXPECTED_DUE_THIS_WEEK,
            week.from().toString(), week.to().toString()));
    }

    /** Manual-payment occurrence counts at the hub's explicit calendar day (§9.1). */
    public StatusResponse.ManualToPay manualToPay(LocalDate today) {
        return read(conn -> new StatusResponse.ManualToPay(
            count(conn, HubSql.MANUAL_TO_PAY_UPCOMING, today.toString(), today.toString()),
            count(conn, HubSql.MANUAL_TO_PAY_OVERDUE, today.toString(), today.toString())));
    }

    /** The exact rows behind {@link #manualToPay}, independent of Expected's selected calendar window. */
    private List<ExpectedResponse.ToPay> manualToPayRows(Connection conn, LocalDate today) throws SQLException {
        List<ExpectedResponse.ToPay> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(HubSql.MANUAL_TO_PAY_ROWS)) {
            ps.setString(1, today.toString()); // bucket label
            ps.setString(2, today.toString()); // upcoming lower bound
            ps.setString(3, today.toString()); // upcoming reminder upper bound
            ps.setString(4, today.toString()); // overdue lower bound
            ps.setString(5, today.toString()); // overdue exclusive upper bound
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ExpectedResponse.ToPay(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), LocalDate.parse(rs.getString(6)), nullableDate(rs.getString(7)),
                        nullableDate(rs.getString(8)), rs.getString(9), nullableLong(rs, 10),
                        rs.getString(11), nullableDate(rs.getString(12)), rs.getString(13),
                        rs.getInt(14) != 0, nullableLong(rs, 15), rs.getString(16), rs.getString(18),
                        rs.getInt(17)));
                }
            }
        }
        return rows;
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
                        rows.add(ledgerRow(rs));
                    }
                }
            }
            return new LedgerPage(total, rows);
        });
    }

    /**
     * The ledger rows for a set of ids, in id order (V2-INCREMENTAL-REFRESH-PLAN.md §4): the exact
     * {@link #ledger} shape, read back after a row-scoped patch so the SSE delta can carry the rows
     * the patch moved rather than ids every client would have to fetch again. Empty ids read
     * nothing.
     */
    public List<LedgerRow> ledgerByIds(java.util.Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<String> ordered = new ArrayList<>(new TreeSet<>(ids));
        String placeholders = String.join(",", java.util.Collections.nCopies(ordered.size(), "?"));
        return read(conn -> {
            List<LedgerRow> rows = new ArrayList<>();
            String sql = HubSql.LEDGER_SELECT + " WHERE t.external_id IN (" + placeholders + ")"
                + " ORDER BY t.external_id";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < ordered.size(); i++) {
                    ps.setString(i + 1, ordered.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(ledgerRow(rs));
                    }
                }
            }
            return rows;
        });
    }

    /** One {@code LEDGER_SELECT} row, shared by {@link #ledger} and {@link #ledgerByIds}. */
    private static LedgerRow ledgerRow(ResultSet rs) throws SQLException {
        return new LedgerRow(rs.getString(1), rs.getLong(2), rs.getString(3),
            LocalDate.parse(rs.getString(4)), rs.getLong(5), rs.getLong(6), rs.getString(7),
            rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
            rs.getString(12), rs.getString(13), rs.getString(14), rs.getInt(15) != 0,
            rs.getString(16), rs.getInt(17) != 0, rs.getString(18), rs.getString(19), rs.getString(20));
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
        if (q.role() != null) {
            conditions.add("t.role = ?");
            params.add(q.role());
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

    public List<ReviewRow> review(String kind, String account, TransferRules rules) {
        return read(conn -> {
            List<String> conditions = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            if (kind != null && !kind.isBlank()) {
                conditions.add("r.kind = ?");
                params.add(kind);
            }
            if (account != null && !account.isBlank()) {
                conditions.add("COALESCE(x.account_ref, CASE WHEN r.kind = 'BALANCE_BREAK' THEN r.subject END) = ?");
                params.add(account);
            }
            String sql = HubSql.REVIEW_SELECT
                + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions))
                + HubSql.REVIEW_ORDER;
            List<ReviewRow> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long stakeRaw = rs.getLong(4);
                        Long stake = rs.wasNull() ? null : stakeRaw;
                        String description = rs.getString(7);
                        String date = rs.getString(8);
                        String subject = rs.getString(1);
                        String rowKind = rs.getString(2);
                        List<ReviewMember> members = isCluster(rowKind)
                            ? clusterMembers(conn, subject, rowKind, rules) : List.of();
                        // A SUSPECTED_RECURRING subject is the grouping stem, not a fact id: the
                        // candidate's own derived facts render from the commitment row (§2.8).
                        ReviewRow.Enrichment enrichment = ReviewItem.SUSPECTED_RECURRING.equals(rowKind)
                            ? candidateEnrichment(conn, subject) : null;
                        rows.add(new ReviewRow(subject, rowKind, rs.getString(3), stake,
                            Instant.parse(rs.getString(5)), rs.getString(6),
                            description == null ? null : trex.v2.core.Clean.clean(description),
                            date == null ? null : LocalDate.parse(date), members, rs.getString(9),
                            rs.getObject(10) == null ? null : rs.getLong(10), enrichment));
                    }
                }
            }
            return rows;
        });
    }

    private static boolean isCluster(String kind) {
        return ReviewItem.POTENTIAL_DUP.equals(kind) || ReviewItem.RESTATEMENT.equals(kind);
    }

    /**
     * The candidate facts behind a {@code SUSPECTED_RECURRING} subject (§2.8). The subject is the
     * grouping stem and the {@code commitment} table keys candidates as {@code cand|<hex>}, so the
     * join mints the id with the detector's own {@link Commitments#candidateId} — one function,
     * never a second hash that could drift — and a stem with no row (a suppressed or ended
     * candidate) yields a null enrichment.
     */
    private ReviewRow.Enrichment candidateEnrichment(Connection conn, String stem) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(HubSql.COMMITMENT_CANDIDATE)) {
            ps.setString(1, Commitments.candidateId(stem));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ReviewRow.Enrichment(rs.getString(1), nullableDate(rs.getString(2)),
                    nullableDate(rs.getString(3)), rs.getInt(4), nullableLong(rs, 5),
                    nullableDouble(rs, 6), nullableDouble(rs, 7));
            }
        }
    }

    /**
     * The cluster a POTENTIAL_DUP/RESTATEMENT subject belongs to (V2-PROPOSAL.md §9.9.F). The
     * derivation stores only the subject and a summary, so the members are reconstructed here with
     * the <em>same</em> predicate and {@link MerchantStem} the derivation used — one account-day at
     * a time — which is what lets the queue show the balance, receipt and verbatim text that tell
     * the rows apart.
     */
    private List<ReviewMember> clusterMembers(Connection conn, String subject, String kind,
                                              TransferRules rules) throws SQLException {
        String account;
        String date;
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT account_ref, date FROM txn_current WHERE external_id = ?")) {
            ps.setString(1, subject);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return List.of();
                }
                account = rs.getString(1);
                date = rs.getString(2);
            }
        }
        List<DayRow> day = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT external_id, n, date, amount, balance, receipt, raw_description, leg "
                + "FROM txn_current WHERE account_ref = ? AND date = ? ORDER BY n")) {
            ps.setString(1, account);
            ps.setString(2, date);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    day.add(new DayRow(rs.getString(1), rs.getLong(2), LocalDate.parse(rs.getString(3)),
                        rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8)));
                }
            }
        }
        int size = day.size();
        int[] parent = new int[size];
        for (int i = 0; i < size; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                DayRow a = day.get(i);
                DayRow b = day.get(j);
                if ("MATCHED".equals(a.leg) || "MATCHED".equals(b.leg)) {
                    continue;
                }
                boolean link;
                if (ReviewItem.POTENTIAL_DUP.equals(kind)) {
                    boolean sameSign = (a.amount < 0) == (b.amount < 0);
                    link = sameSign
                        && MerchantStem.stem(a.raw).equals(MerchantStem.stem(b.raw))
                        && Math.abs(a.amount - b.amount) <= rules.dupTolerance();
                } else {
                    link = a.amount == b.amount
                        && MerchantStem.restatement(a.raw, b.raw, rules.restatementOverlap());
                }
                if (link) {
                    int ra = find(parent, i);
                    int rb = find(parent, j);
                    if (ra != rb) {
                        parent[rb] = ra;
                    }
                }
            }
        }
        int root = -1;
        for (int i = 0; i < size; i++) {
            if (day.get(i).id.equals(subject)) {
                root = find(parent, i);
                break;
            }
        }
        if (root < 0) {
            return List.of();
        }
        List<ReviewMember> members = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (find(parent, i) == root) {
                DayRow d = day.get(i);
                members.add(new ReviewMember(d.id, d.n, d.date, d.amount, d.balance, d.receipt,
                    trex.v2.core.Clean.clean(d.raw)));
            }
        }
        return members;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private record DayRow(String id, long n, LocalDate date, long amount, long balance,
                          String receipt, String raw, String leg) {}

    /** The note thread on one id (or every note), oldest first (§6.2 {@code NOTE}). */
    public List<NoteJson> notes(String externalId) {
        return read(conn -> {
            List<NoteJson> out = new ArrayList<>();
            boolean one = externalId != null && !externalId.isBlank();
            try (PreparedStatement ps = conn.prepareStatement(one ? HubSql.NOTES_FOR_SELECT : HubSql.NOTES_SELECT)) {
                if (one) {
                    ps.setString(1, externalId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new NoteJson(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                            Instant.parse(rs.getString(5))));
                    }
                }
            }
            return out;
        });
    }

    /** Effective DISMISS decisions, newest first, so a silenced item's reason stays visible (§9.9.F). */
    public List<DismissalJson> dismissals() {
        return read(conn -> {
            List<DismissalJson> out = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(HubSql.DISMISSALS_SELECT)) {
                while (rs.next()) {
                    out.add(new DismissalJson(rs.getString(1), jsonIds(rs.getString(2)), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6)));
                }
            }
            return out;
        });
    }

    private static List<String> jsonIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return trex.v2.log.Json.mapper().readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return List.of();
        }
    }

    // ---- commitments (V2-COMMITMENTS-PLAN.md §2.7, §2.8) -------------------------------------

    /** The registry: every candidate and declared row, each with its rules and notes thread. */
    public List<CommitmentJson> commitments() {
        return read(conn -> {
            Map<String, List<CommitmentJson.RuleJson>> rules = new LinkedHashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(HubSql.COMMITMENT_RULES_SELECT)) {
                while (rs.next()) {
                    rules.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                        .add(new CommitmentJson.RuleJson(rs.getString(2), rs.getString(3)));
                }
            }
            Map<String, List<NoteJson>> notes = new LinkedHashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(HubSql.COMMITMENT_NOTES_SELECT)) {
                while (rs.next()) {
                    notes.computeIfAbsent(rs.getString(2), k -> new ArrayList<>())
                        .add(new NoteJson(rs.getString(2), rs.getString(3), rs.getLong(1),
                            rs.getString(4), Instant.parse(rs.getString(5))));
                }
            }
            List<CommitmentJson> out = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(HubSql.COMMITMENTS_SELECT)) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    out.add(new CommitmentJson(id, rs.getString(2), rs.getString(25),
                        rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        nullableDate(rs.getString(9)), nullableDate(rs.getString(10)),
                        nullableDate(rs.getString(11)), nullableLong(rs, 12), nullableLong(rs, 13),
                        nullableDouble(rs, 14), nullableDate(rs.getString(15)), rs.getInt(16),
                        rs.getInt(17), nullableDouble(rs, 18), rs.getInt(19) != 0, rs.getInt(20),
                        nullableLong(rs, 21), nullableLong(rs, 22), nullableLong(rs, 23),
                        nullableDate(rs.getString(24)), rules.getOrDefault(id, List.of()),
                        nullableDate(rs.getString(26)), notes.getOrDefault(id, List.of()), rs.getString(27),
                        nullableInteger(rs, 28)));
                }
            }
            return out;
        });
    }

    /**
     * A commitment's activity for its menu (V2-EXPECTED-UX-PLAN.md §7 Stage 2): a detected
     * candidate's series facts (the same frozen-stem lens the detector grouped with), or every
     * current fact a declared commitment's effective rules match — **unbounded history**, not the
     * twelve-month occurrence window — life-bounded at {@code endedAt} when retired. Rule matching
     * goes through {@link CommitmentRules}, one compiler with the derive. Oldest first; an unknown
     * id is an empty list.
     */
    public List<ActivityJson> activity(String commitmentId) {
        return read(conn -> {
            String stem = null;
            String direction = null;
            LocalDate endedAt = null;
            try (PreparedStatement ps = conn.prepareStatement(HubSql.COMMITMENT_BY_ID)) {
                ps.setString(1, commitmentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        stem = rs.getString(1);
                        direction = rs.getString(2);
                        endedAt = nullableDate(rs.getString(3));
                    }
                }
            }
            // A candidate is a proposal, not an association, so it has no reverse-map rows: its
            // activity is the frozen-stem series (V2-COMMITMENT-FACT-PLAN.md §3.3).
            if (stem != null) {
                List<ActivityJson> out = new ArrayList<>();
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(HubSql.ACTIVITY_FACTS)) {
                    while (rs.next()) {
                        if (stem.equals(MerchantStem.stem(rs.getString(5)))) {
                            out.add(new ActivityJson(LocalDate.parse(rs.getString(2)),
                                rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(1),
                                null, false));
                        }
                    }
                }
                return out;
            }

            // A declared commitment: the reverse map is the exact binding (rule or pin, unbounded),
            // plus the facts the person excluded that still match the rules, so Include works. A
            // fact claimed by another commitment, or a stale exclusion that no longer matches a
            // rule, is not listed here — the winner owns it.
            List<CommitmentRules.CompiledRule> rules = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(HubSql.COMMITMENT_RULES_FOR)) {
                ps.setString(1, commitmentId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rules.add(CommitmentRules.compile(new CommitmentRule(commitmentId,
                            rs.getString(1), rs.getString(2), 0L)));
                    }
                }
            }
            List<ActivityFactRow> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(HubSql.COMMITMENT_FACTS_FOR)) {
                ps.setString(1, commitmentId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        LocalDate date = LocalDate.parse(rs.getString(2));
                        rows.add(new ActivityFactRow(date, rs.getLong(6), new ActivityJson(date,
                            rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(1),
                            rs.getString(7), false)));
                    }
                }
            }
            boolean outgoing = !"in".equals(direction);
            try (PreparedStatement ps = conn.prepareStatement(HubSql.COMMITMENT_EXCLUDED_FACTS_FOR)) {
                ps.setString(1, commitmentId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long amount = rs.getLong(4);
                        LocalDate date = LocalDate.parse(rs.getString(2));
                        if (amount == 0 || outgoing != (amount < 0)) {
                            continue;
                        }
                        if (endedAt != null && date.isAfter(endedAt)) {
                            continue;
                        }
                        if (!CommitmentRules.anyMatch(rules, rs.getString(5), rs.getString(3), amount)) {
                            continue;
                        }
                        rows.add(new ActivityFactRow(date, rs.getLong(6), new ActivityJson(date,
                            rs.getString(3), amount, rs.getString(5), rs.getString(1), "rule", true)));
                    }
                }
            }
            rows.sort(Comparator.comparing(ActivityFactRow::date)
                .thenComparingLong(ActivityFactRow::n));
            return rows.stream().map(ActivityFactRow::json).toList();
        });
    }

    /**
     * The Expected view (§2.8) for one calendar window: the window's occurrences, the whole
     * arrears backlog (the holes, oldest first, running total) and the window's committed totals by
     * direction. A row's committed magnitude is what it carries — the movement attached to the
     * window once a fact landed, the commitment's current price while nothing has — so a settled
     * row contributes its expectation and a due or missed row its current price.
     */
    public ExpectedResponse expected(String window, Period.Range range, LocalDate today) {
        return read(conn -> {
            List<ExpectedResponse.Occurrence> occurrences = new ArrayList<>();
            long out = 0;
            long in = 0;
            try (PreparedStatement ps = conn.prepareStatement(HubSql.EXPECTED_OCCURRENCES)) {
                ps.setString(1, range.from().toString());
                ps.setString(2, range.to().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String direction = rs.getString(3);
                        Long currentAmount = nullableLong(rs, 5);
                        Long amount = nullableLong(rs, 10);
                        occurrences.add(new ExpectedResponse.Occurrence(rs.getString(1),
                            rs.getString(2), direction, rs.getString(4),
                            LocalDate.parse(rs.getString(6)), nullableDate(rs.getString(7)),
                            nullableDate(rs.getString(8)), rs.getString(9), amount,
                            rs.getString(11), nullableDate(rs.getString(12)), rs.getString(13),
                            rs.getInt(14) != 0, nullableLong(rs, 15), rs.getString(16)));
                        long committed = amount != null ? Math.abs(amount)
                            : currentAmount == null ? 0 : Math.abs(currentAmount);
                        if ("out".equals(direction)) {
                            out += committed;
                        } else {
                            in += committed;
                        }
                    }
                }
            }
            List<ExpectedResponse.Arrear> arrears = new ArrayList<>();
            long running = 0;
            try (PreparedStatement ps = conn.prepareStatement(HubSql.ARREARS_OCCURRENCES);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long currentAmount = nullableLong(rs, 5);
                    Long amount = nullableLong(rs, 8);
                    // A hole: nothing landed, so the shortfall is the expectation in full.
                    long expected = currentAmount != null ? Math.abs(currentAmount) : 0;
                    running += expected;
                    arrears.add(new ExpectedResponse.Arrear(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), LocalDate.parse(rs.getString(6)),
                        rs.getString(7), amount, expected, expected, running));
                }
            }
            return new ExpectedResponse(window, range.from(), range.to(), occurrences, arrears,
                new ExpectedResponse.Totals(out, in), null, manualToPayRows(conn, today));
        });
    }

    /**
     * Everything the headroom figure and the budget sheet share, from one row-walk (§9.2,
     * V2-BUDGET-VIEW-PLAN.md §3–4). The two reads call {@link #walk} so they cannot drift; the
     * sheet's {@code left} equals headroom's for the same month. The amounts are the month's real
     * flows — every account's external rows and every commitment's occurrences — with
     * {@code movedOut}/{@code movedIn} carrying only the moves that cross the budget boundary;
     * {@code incomeByCategory} and {@code spendByCategory} are the per-category amounts the sheet
     * rolls up, keyed by trex category; {@code movedOutBy} and {@code movedInBy} are the month's
     * boundary-crossing moves keyed by the account on the other side; {@code outside} is the
     * month's moves between two accounts both outside the budget, one entry per transfer;
     * {@code principalBy} is every repayment to a declared loan account, keyed by that account, and
     * {@code principal} the part of it the operator chose to count as Money Out (D-7; zero when the
     * option is off, so the sheet's {@code left} still equals headroom's by default).
     */
    private record Walk(long incomeIn, long incomeDue, long committedPaid, long committedDue,
                        long uncommittedSpend, long unrecordedCash, long movedOut, long movedIn,
                        long missed, long unpaired, LocalDate through, List<String> accounts,
                        Map<String, Long> incomeByCategory, Map<String, Long> spendByCategory,
                        Map<String, Long> movedOutBy, Map<String, Long> movedInBy,
                        List<BudgetResponse.Outside> outside, long principal,
                        Map<String, Long> principalBy) {

        /** Headroom's formula (§9.2), now the sheet's Money Left Over: {@code principal} is what the
         *  operator asked to count as Money Out, so it is already zero when the option is off. */
        long left() {
            return incomeIn + incomeDue + movedIn - committedPaid - committedDue - uncommittedSpend
                - movedOut - unrecordedCash - principal;
        }
    }

    /** The from/to pair an outside transfer aggregates by (D-3). */
    private record Pair(String from, String to) {}

    /**
     * The headroom of one calendar month (V2-REVIEW-FIXES-PLAN.md §10, V2-SPEC.md §9.2): income and
     * spend as the month's real flows — every account's external rows and every commitment's
     * occurrences, whatever the account's {@code budget} flag — with a move between two of your own
     * accounts never income or spend. A move changes {@code left} only where it crosses into or out
     * of a budget account ({@code movedOut}/{@code movedIn}); between two accounts both outside the
     * budget it changes nothing. A claimed fact counts through its commitment's occurrence only
     * when it is attached to one; a fact claimed but unattached is ordinary income or spend. A
     * commitment whose facts are all transfers between your own accounts is a move; its occurrences
     * are not counted.
     */
    public ExpectedResponse.Headroom headroom(Period.Range month, LocalDate today, java.util.Set<String> budget,
                                             java.util.Set<String> statementLiquid) {
        return read(conn -> {
            // The status strip never counts loan principal as Money Out (D-7): an empty loan set and
            // the option off, so this walk is exactly what it was before the feature.
            Walk w = walk(conn, month, today, budget, statementLiquid, Set.of(), false);
            return new ExpectedResponse.Headroom(month.from(), w.left(), w.incomeIn(), w.incomeDue(),
                w.committedPaid(), w.committedDue(), w.uncommittedSpend(), w.unrecordedCash(),
                w.movedOut(), w.movedIn(), w.missed(), w.unpaired(), w.through(), w.accounts());
        });
    }

    /**
     * The month's budget sheet (V2-SPEC.md §9.2, V2-BUDGET-VIEW-PLAN.md §4): headroom's walk,
     * presented as Money In / Money Out / Left, with each out row rolled into its primary group.
     * {@code groups} maps a trex category to its ezbookkeeping primary (§11.2); an unmapped or blank
     * category falls back to one {@code Expense} group. Income is never grouped. The synthetic
     * slices — {@code Due}, {@code Unrecorded} — sit after the groups and are not posted spending;
     * the group slices alone sum to {@code spend.posted} and every slice to {@code spend.total}, so
     * the donut reconciles with {@code left}. A move is never a slice: it rides {@code moved}, whose
     * {@code outside} list holds the moves between two non-budget accounts, aggregated per pair with
     * its payment count; the budget-boundary moves ride {@code moved.out}/{@code moved.in} with
     * their parts (D-2, D-3).
     *
     * <p>{@code loans} is the declared loan accounts ({@code mortgages.yaml}) and {@code
     * includePrincipal} the operator's option (D-7): a transfer paying one of those accounts is
     * always reported as {@link BudgetResponse.Principal}, and, only when the option is on, it also
     * becomes a {@code Loan principal} slice (after the groups, before {@code Due}), enters {@code
     * spend.total} and lowers {@code left}; when the option is off it stays a move in {@code moved}.
     */
    public BudgetResponse budgetSheet(Period.Range month, LocalDate today, java.util.Set<String> budget,
                                      java.util.Set<String> statementLiquid, Map<String, String> groups,
                                      Set<String> loans, boolean includePrincipal) {
        return read(conn -> {
            Walk w = walk(conn, month, today, budget, statementLiquid, loans, includePrincipal);

            // Each group keeps its parts — the trex categories inside it — so the view can open a
            // group into the distribution that made it up (V2-BUDGET-VIEW-PLAN.md §5).
            Map<String, Map<String, Long>> byGroup = new java.util.TreeMap<>();
            w.spendByCategory().forEach((category, amount) ->
                byGroup.computeIfAbsent(groupOf(category, groups), k -> new java.util.TreeMap<>())
                    .merge(category, amount, Long::sum));
            List<BudgetResponse.Slice> slices = new ArrayList<>();
            byGroup.forEach((group, categories) -> {
                List<BudgetResponse.Part> parts = parts(categories);
                long amount = parts.stream().mapToLong(BudgetResponse.Part::amount).sum();
                slices.add(new BudgetResponse.Slice(group, amount, parts));
            });
            slices.sort(java.util.Comparator.comparingLong(BudgetResponse.Slice::amount).reversed()
                .thenComparing(BudgetResponse.Slice::group));
            long posted = w.committedPaid() + w.uncommittedSpend();
            long due = w.committedDue();
            // The principal is always reported; it is only Money Out (a slice, in the total, in
            // `left`) when the operator asked for it (D-7).
            List<BudgetResponse.Part> principalParts = parts(w.principalBy());
            long principal = w.principalBy().values().stream().mapToLong(Long::longValue).sum();
            long total = posted + due + w.unrecordedCash() + (includePrincipal ? principal : 0);
            if (includePrincipal && principal != 0) {
                slices.add(new BudgetResponse.Slice("Loan principal", principal, principalParts));
            }
            if (due != 0) {
                slices.add(new BudgetResponse.Slice("Due", due));
            }
            if (w.unrecordedCash() != 0) {
                slices.add(new BudgetResponse.Slice("Unrecorded", w.unrecordedCash()));
            }

            List<BudgetResponse.Line> lines = w.incomeByCategory().entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new BudgetResponse.Line(e.getKey(), e.getValue()))
                .toList();

            BudgetResponse.Moved moved = new BudgetResponse.Moved(w.movedOut(), w.movedIn(),
                w.movedIn() - w.movedOut(), parts(w.movedOutBy()), parts(w.movedInBy()),
                aggregateOutside(w.outside()));

            return new BudgetResponse(YearMonth.from(month.from()).toString(), w.through(), w.accounts(),
                new BudgetResponse.Income(w.incomeIn(), w.incomeDue(), w.incomeIn() + w.incomeDue(), lines),
                new BudgetResponse.Spend(posted, due, w.unrecordedCash(), total, slices),
                moved, new BudgetResponse.Principal(principal, principalParts),
                w.missed(), w.unpaired(), w.left());
        });
    }

    /**
     * One slice's parts, amount descending then label: a group's trex categories, a {@code Moved}
     * out's destination accounts, or the move in's source accounts.
     */
    private static List<BudgetResponse.Part> parts(Map<String, Long> amounts) {
        return amounts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .map(e -> new BudgetResponse.Part(e.getKey(), e.getValue()))
            .toList();
    }

    /**
     * The month's outside transfers, one line per pair (D-3): the walk's per-transfer entries summed
     * with the payments behind them, sorted by {@code from} then {@code to}.
     */
    private static List<BudgetResponse.Outside> aggregateOutside(List<BudgetResponse.Outside> perTransfer) {
        Map<Pair, long[]> byPair = new java.util.TreeMap<>(
            Comparator.comparing(Pair::from).thenComparing(Pair::to));
        for (BudgetResponse.Outside transfer : perTransfer) {
            long[] sum = byPair.computeIfAbsent(new Pair(transfer.from(), transfer.to()),
                k -> new long[2]);
            sum[0] += transfer.amount();
            sum[1]++;
        }
        List<BudgetResponse.Outside> outside = new ArrayList<>();
        byPair.forEach((pair, sum) ->
            outside.add(new BudgetResponse.Outside(pair.from(), pair.to(), sum[0], (int) sum[1])));
        return outside;
    }

    /**
     * The one row-walk the headroom figure and the budget sheet share (§9.2). It reads the month's
     * facts ({@link HubSql#HEADROOM_ROWS}) and its commitments' occurrences
     * ({@link HubSql#EXPECTED_OCCURRENCES}) and returns every amount the two reads need, plus the
     * sheet's per-category detail: the category of the fact each commitment claimed (so a paid
     * occurrence rolls into that category's group), the category of every uncommitted out row, and
     * the month's moves between your own accounts — those crossing the budget boundary (keyed by the
     * contra account) and those between two non-budget accounts (D-2, D-3). The budget sheet and
     * headroom read the same walk.
     *
     * <p>{@code loans} is the declared loan accounts and {@code includePrincipal} the operator's
     * option (D-7): a paying transfer to a loan account is always collected into {@code principalBy},
     * keyed by that loan account; only when the option is on is it skipped as a move (so it is not
     * also in {@code outside}/{@code movedOut}) and subtracted from {@code left} via {@code
     * principal}. Headroom passes an empty set and {@code false}, so the strip is untouched.
     */
    private Walk walk(Connection conn, Period.Range month, LocalDate today, java.util.Set<String> budget,
                      java.util.Set<String> statementLiquid, Set<String> loans, boolean includePrincipal)
            throws SQLException {
        // A commitment whose claimed facts are only ever internal moves between budget accounts (a
        // card repayment), and one whose facts are only ever transfers at all (a loan repayment):
        // both are moves, never income or spend, so their occurrences are not counted (§9.2).
        Map<String, Boolean> internalOnly = new java.util.TreeMap<>();
        Map<String, Boolean> transferOnly = new java.util.TreeMap<>();
        try (PreparedStatement ps = conn.prepareStatement(HubSql.COMMITMENT_FACT_ACCOUNTS);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString(1);
                boolean internal = rs.getString(3) != null && budget.contains(rs.getString(2))
                    && budget.contains(rs.getString(4));
                internalOnly.merge(id, internal, Boolean::logicalAnd);
                transferOnly.merge(id, rs.getInt(5) != 0, Boolean::logicalAnd);
            }
        }

        // The month's rows first: they carry the category of the fact each commitment claimed, so
        // a paid occurrence below can roll into that category, and they carry every move between
        // your own accounts (D-2, D-3). Collected before the occurrences because of the first.
        Map<String, String> commitmentCategory = new java.util.TreeMap<>();
        Map<String, Long> incomeByCategory = new java.util.TreeMap<>();
        Map<String, Long> spendByCategory = new java.util.TreeMap<>();
        Map<String, Long> movedOutBy = new java.util.TreeMap<>();
        Map<String, Long> movedInBy = new java.util.TreeMap<>();
        Map<String, Long> principalBy = new java.util.TreeMap<>();
        List<BudgetResponse.Outside> outside = new ArrayList<>();
        long uncommittedSpend = 0;
        long movedOut = 0;
        long movedIn = 0;
        long unpaired = 0;
        long incomeIn = 0;
        try (PreparedStatement ps = conn.prepareStatement(HubSql.HEADROOM_ROWS)) {
            ps.setString(1, month.from().toString());
            ps.setString(2, month.to().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String account = rs.getString(1);
                    long amount = rs.getLong(2);
                    String role = rs.getString(3);
                    String commitmentId = rs.getString(6);
                    String other = rs.getString(7);
                    String category = rs.getString(8);
                    boolean attached = rs.getString(9) != null;
                    if (commitmentId != null && category != null) {
                        commitmentCategory.putIfAbsent(commitmentId, category);
                    }
                    if ("noop".equals(role)) {
                        continue; // not a posting
                    }
                    String transferId = rs.getString(5);
                    if (transferId != null && other != null) {
                        // A transfer whose paying leg went to a declared loan account is a principal
                        // repayment (D-7): always collected into `principal`, and — only when the
                        // operator asked — skipped as a move so it is not also in outside/movedOut.
                        boolean principal = amount < 0 && loans.contains(other);
                        if (principal) {
                            principalBy.merge(other, -amount, Long::sum);
                            if (includePrincipal) {
                                continue;
                            }
                        }
                        // A transfer between two of your own accounts is a move, never income or
                        // spend. It moves `left` only from the budget side of a boundary crossing;
                        // between two accounts both outside the budget it changes nothing and is
                        // reported once, by its paying leg (D-3); between two budget accounts it
                        // moves nothing at all.
                        boolean accountIn = budget.contains(account);
                        boolean otherIn = budget.contains(other);
                        if (!accountIn && !otherIn) {
                            if (amount < 0) {
                                outside.add(new BudgetResponse.Outside(account, other, -amount, 1));
                            }
                        } else if (accountIn && !otherIn) {
                            if (amount < 0) {
                                movedOut += -amount;
                                movedOutBy.merge(other, -amount, Long::sum);
                            } else {
                                movedIn += amount;
                                movedInBy.merge(other, amount, Long::sum);
                            }
                        }
                        continue;
                    }
                    if (attached) {
                        continue; // counted through its commitment's occurrence, never twice
                    }
                    if ("HELD".equals(rs.getString(4))) {
                        unpaired += Math.abs(amount);
                        continue;
                    }
                    // Every other row counts as income or spend, whatever the account's budget
                    // flag: a salary paid into a savings account or an interest charge on a loan is
                    // real money in or out (§9.2).
                    if (amount < 0) {
                        uncommittedSpend += -amount;
                        spendByCategory.merge(categoryOf(category), -amount, Long::sum);
                    } else {
                        incomeIn += amount;
                        incomeByCategory.merge(categoryOf(category), amount, Long::sum);
                    }
                }
            }
        }
        outside.sort(Comparator.comparing(BudgetResponse.Outside::from)
            .thenComparing(BudgetResponse.Outside::to));

        long incomeDue = 0;
        long committedPaid = 0;
        long committedDue = 0;
        long missed = 0;
        try (PreparedStatement ps = conn.prepareStatement(HubSql.EXPECTED_OCCURRENCES)) {
            ps.setString(1, month.from().toString());
            ps.setString(2, month.to().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    if (Boolean.TRUE.equals(internalOnly.get(id))
                        || Boolean.TRUE.equals(transferOnly.get(id))) {
                        continue; // a move between your own accounts, never income or spend
                    }
                    boolean in = "in".equals(rs.getString(3));
                    Long expected = nullableLong(rs, 5);
                    Long amount = nullableLong(rs, 10);
                    long moved = amount != null ? Math.abs(amount) : 0;
                    long owed = expected == null ? 0 : Math.abs(expected);
                    // A paid occurrence carries the movement attached to it; its category is the
                    // one the claimed fact carried, so it lands in the same slice as a direct
                    // purchase would (§3).
                    String category = categoryOf(commitmentCategory.get(id));
                    switch (rs.getString(9)) {
                        case "occurred", "settled", "partial" -> {
                            if (in) {
                                incomeIn += moved;
                                incomeByCategory.merge(category, moved, Long::sum);
                            } else {
                                committedPaid += moved;
                                spendByCategory.merge(category, moved, Long::sum);
                            }
                        }
                        case "due", "awaiting" -> {
                            if (in) {
                                incomeDue += owed;
                            } else {
                                committedDue += owed;
                            }
                        }
                        case "missed" -> {
                            if (!in) {
                                missed += owed;
                            }
                        }
                        default -> { }
                    }
                }
            }
        }

        LocalDate through = oldestFrontier(conn, today, statementLiquid);
        // The month's unrecorded cash (§9.2): the cash_month figure over the wallets in the
        // budget. cash_month only ever holds declared wallets, but a wallet with budget: false
        // must still be left out, exactly as its spend is.
        long unrecordedCash = 0;
        try (PreparedStatement ps = conn.prepareStatement(HubSql.CASH_MONTH_UNRECORDED_SELECT)) {
            ps.setString(1, YearMonth.from(month.from()).toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (budget.contains(rs.getString(1))) {
                        unrecordedCash += rs.getLong(2);
                    }
                }
            }
        }
        // The principal counts toward `left` only when it is Money Out, so headroom's walk (empty
        // loans, option off) keeps the exact figure it always had (D-7).
        long principal = includePrincipal
            ? principalBy.values().stream().mapToLong(Long::longValue).sum() : 0;
        return new Walk(incomeIn, incomeDue, committedPaid, committedDue, uncommittedSpend,
            unrecordedCash, movedOut, movedIn, missed, unpaired, through,
            List.copyOf(new java.util.TreeSet<>(budget)), Map.copyOf(incomeByCategory),
            Map.copyOf(spendByCategory), Map.copyOf(movedOutBy), Map.copyOf(movedInBy),
            List.copyOf(outside), principal, Map.copyOf(principalBy));
    }

    /** A row's trex category, or {@code UNCATEGORIZED} for a blank one (a structural transfer leg). */
    private static String categoryOf(String category) {
        return category == null || category.isBlank() ? RuleSet.UNCATEGORIZED : category;
    }

    /**
     * The primary group an expense category belongs to (§11.2): its entry in the {@code ezb.yaml}
     * map, or one {@code Expense} fallback when the map does not name it.
     */
    private static String groupOf(String category, Map<String, String> groups) {
        String group = groups.get(category);
        return group == null || group.isBlank() ? "Expense" : group;
    }

    /**
     * Every account's frontier — its newest non-synthetic transaction date at or before
     * {@code today} (V2-QOL-IMPROVEMENTS-PLAN.md §2); an account with no such row is absent (nothing to
     * fetch yet).
     */
    public Map<String, LocalDate> frontiers(LocalDate today) {
        return read(conn -> {
            Map<String, LocalDate> out = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(HubSql.ACCOUNT_FRONTIERS)) {
                ps.setString(1, today.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), LocalDate.parse(rs.getString(2)));
                    }
                }
            }
            return out;
        });
    }

    private static LocalDate oldestFrontier(Connection conn, LocalDate today, java.util.Set<String> accounts)
        throws SQLException {
        LocalDate through = null;
        try (PreparedStatement ps = conn.prepareStatement(HubSql.ACCOUNT_FRONTIERS)) {
            ps.setString(1, today.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (accounts.contains(rs.getString(1))) {
                        LocalDate frontier = LocalDate.parse(rs.getString(2));
                        if (through == null || frontier.isBefore(through)) {
                            through = frontier;
                        }
                    }
                }
            }
        }
        return through;
    }

    public List<TransferJson> transfers() {
        return read(conn -> {
            List<TransferJson> rows = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(HubSql.TRANSFERS_SELECT)) {
                while (rs.next()) {
                    long decisionN = rs.getLong(6);
                    rows.add(new TransferJson(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.wasNull() ? null : decisionN,
                        rs.getString(7), rs.getString(8), Instant.parse(rs.getString(9))));
                }
            }
            return rows;
        });
    }

    /** The derived per-month unrecorded cash rows (§9.4); {@code account} null or blank is all. */
    public List<CashMonthJson> cashMonths(String account) {
        return read(conn -> {
            boolean filtered = account != null && !account.isBlank();
            String sql = filtered ? HubSql.CASH_MONTHS_FOR_SELECT : HubSql.CASH_MONTHS_SELECT;
            List<CashMonthJson> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (filtered) {
                    ps.setString(1, account);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long heldOpen = rs.getLong(7);
                        boolean nullOpen = rs.wasNull();
                        long heldClose = rs.getLong(8);
                        boolean nullClose = rs.wasNull();
                        rows.add(new CashMonthJson(rs.getString(1), rs.getString(2), rs.getLong(3),
                            rs.getLong(4), rs.getLong(5), rs.getLong(6), nullOpen ? null : heldOpen,
                            nullClose ? null : heldClose, rs.getLong(9), rs.getLong(10),
                            rs.getInt(11) != 0));
                    }
                }
            }
            return rows;
        });
    }

    /** The derived transfers as core models, for openings and the clearing check (§6.10). */
    public List<trex.v2.core.derive.TransferRow> transferRows() {
        return read(conn -> {
            List<trex.v2.core.derive.TransferRow> rows = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(HubSql.TRANSFERS_SELECT)) {
                while (rs.next()) {
                    long decisionN = rs.getLong(6);
                    boolean nullDecision = rs.wasNull();
                    rows.add(new trex.v2.core.derive.TransferRow(rs.getString(1), rs.getString(2),
                        rs.getString(3), trex.v2.core.derive.Confidence.valueOf(rs.getString(4)),
                        rs.getString(5), nullDecision ? null : decisionN,
                        trex.v2.core.Rail.fromWire(rs.getString(7)), Instant.parse(rs.getString(9)),
                        rs.getString(8)));
                }
            }
            return rows;
        });
    }

    /** Superseded ids and where they resolve now: how the egress re-keys a group instead of orphaning it. */
    public Map<String, String> resolvedIds() {
        return read(conn -> {
            Map<String, String> out = new java.util.TreeMap<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(HubSql.RESOLVED_IDS)) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
            return out;
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
                    legs.put(rs.getString(1), new String[] { rs.getString(2), rs.getString(3), rs.getString(4) });
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
                        if (pair == null) {
                            throw new IllegalStateException("unit " + unitId + " has no transfer row");
                        }
                        String clearing = pair[2];
                        if (clearing != null) {
                            // One real leg and an account side (§6.7): the clearing ref stands in the
                            // transfer row where a leg id would be, so it is never a fact.
                            boolean realPays = !clearing.equals(pair[0]);
                            trex.v2.core.Fact real = facts.get(realPays ? pair[0] : pair[1]);
                            if (real == null) {
                                throw new IllegalStateException("clearing unit " + unitId + " has no current real leg");
                            }
                            String fromRef = realPays ? real.accountRef() : clearing;
                            String toRef = realPays ? clearing : real.accountRef();
                            long amount = Math.abs(real.amount());
                            out.add(new trex.v2.hub.api.ProjectionUnit(unitId, "TRANSFER", real.n(), fromRef,
                                toRef, real.date(), amount, currency, "TRANSFER", "STRUCTURAL", "MATCHED",
                                false, false, real.rawDescription(),
                                unitHash(unitId, "TRANSFER", fromRef, toRef, real.date(), amount, currency,
                                    "TRANSFER"), List.of(pair[0], pair[1])));
                            continue;
                        }
                        trex.v2.core.Fact from = facts.get(pair[0]);
                        trex.v2.core.Fact to = facts.get(pair[1]);
                        if (from == null || to == null) {
                            throw new IllegalStateException("transfer unit " + unitId + " has a leg that is not current");
                        }
                        // A TRANSFER unit's accountRef is the paying (negative) leg's account
                        // (V2-SPEC.md §11.2). The matcher stores from/to in iteration order, not by
                        // sign, so orient here. Swap the refs only: date and rawDescription stay the
                        // matcher's (canonical), so a pair whose legs are days apart keeps the date it
                        // has today. A PAIR that names no negative leg (both legs the same sign) has no
                        // payer to name, so the matcher order stands.
                        boolean swap = from.amount() > 0 && to.amount() < 0;
                        String payingRef = swap ? to.accountRef() : from.accountRef();
                        String receivingRef = swap ? from.accountRef() : to.accountRef();
                        out.add(new trex.v2.hub.api.ProjectionUnit(unitId, "TRANSFER",
                            Math.max(from.n(), to.n()), payingRef, receivingRef, from.date(),
                            Math.abs(from.amount()), currency, "TRANSFER", "STRUCTURAL", "MATCHED",
                            false, false, from.rawDescription(),
                            unitHash(unitId, "TRANSFER", payingRef, receivingRef, from.date(),
                                Math.abs(from.amount()), currency, "TRANSFER"), List.of(pair[0], pair[1])));
                    } else {
                        trex.v2.core.Fact fact = facts.get(unitId);
                        if (fact == null) {
                            throw new IllegalStateException("unit " + unitId + " has no current fact");
                        }
                        out.add(new trex.v2.hub.api.ProjectionUnit(unitId, "EXTERNAL", fact.n(),
                            fact.accountRef(), null, fact.date(), fact.amount(), currency, rs.getString(7),
                            rs.getString(8), rs.getString(9), rs.getInt(10) != 0, rs.getInt(11) != 0,
                            fact.rawDescription(),
                            unitHash(unitId, "EXTERNAL", fact.accountRef(), null, fact.date(), fact.amount(),
                                currency, rs.getString(7)), List.of()));
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

    /**
     * A commitment as the decision prechecks need it, or null when no row exists at all:
     * {@code declared} is origin {@code declared} (a detected candidate is known but the fold
     * cannot apply a pin/note/settle to it), {@code retired} is a set {@code retired_n}.
     */
    public CommitmentRef commitmentRef(String commitmentId) {
        return read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(HubSql.COMMITMENT_REF)) {
                ps.setString(1, commitmentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    boolean declared = "declared".equals(rs.getString(1));
                    long retiredN = rs.getLong(2);
                    return new CommitmentRef(declared, !rs.wasNull(), rs.getString(3),
                        nullableDate(rs.getString(4)));
                }
            }
        });
    }

    /** The two faces a commitment precheck asks about (§2.6). */
    /**
     * A commitment's routing state for a precheck: whether it is declared (not a detected
     * candidate), whether it is retired, and the schedule a re-declare must not move
     * ({@link #cadence()}/{@link #anchor()}, V2-SPEC.md §5).
     */
    public record CommitmentRef(boolean declared, boolean retired, String cadence, LocalDate anchor) {}

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

    /** One activity row with its log order, so the map and excluded rows merge chronologically. */
    private record ActivityFactRow(LocalDate date, long n, ActivityJson json) {}

    // ---- helpers ----------------------------------------------------------------------------

    private static int bind(PreparedStatement ps, List<Object> params) throws SQLException {
        int index = 1;
        for (Object value : params) {
            ps.setObject(index++, value);
        }
        return index;
    }

    /** A nullable INTEGER column; SQLite's {@code getLong} would coerce NULL to 0. */
    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** A nullable INTEGER column represented as {@link Integer}. */
    private static Integer nullableInteger(ResultSet rs, int column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** A nullable REAL column. */
    private static Double nullableDouble(ResultSet rs, int column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    /** A nullable ISO date column. */
    private static LocalDate nullableDate(String value) {
        return value == null ? null : LocalDate.parse(value);
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
