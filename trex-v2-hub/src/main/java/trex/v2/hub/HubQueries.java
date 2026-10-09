package trex.v2.hub;

import trex.v2.hub.api.ActivityJson;
import trex.v2.hub.api.CommitmentJson;
import trex.v2.hub.api.DismissalJson;
import trex.v2.hub.api.ExpectedResponse;
import trex.v2.hub.api.LedgerPage;
import trex.v2.hub.api.LedgerRow;
import trex.v2.hub.api.NoteJson;
import trex.v2.hub.api.ReviewMember;
import trex.v2.hub.api.ReviewRow;
import trex.v2.hub.api.TransferJson;
import trex.v2.hub.api.ProjectionUnit;
import trex.v2.core.MerchantStem;
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

    /** The ingest history, paired from the markers (V2-PROPOSAL.md §12.6), newest first. */
    public List<trex.v2.hub.api.IngestsResponse.IngestRow> ingests(int limit) {
        LocalDate asOf = LocalDate.now();
        return read(conn -> {
            List<trex.v2.hub.api.IngestsResponse.IngestRow> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT batch, file, evidence_id, account_ref, n_start, n_end, appended, duplicate, flagged, "
                    + "status, started_ms, completed_ms, "
                    + "(SELECT MAX(t.date) FROM txn_current t WHERE t.account_ref = ingest_batch.account_ref "
                    + "AND t.date <= ?) FROM ingest_batch ORDER BY n_start DESC LIMIT ?")) {
                ps.setString(1, asOf.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new trex.v2.hub.api.IngestsResponse.IngestRow(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getLong(5), rs.getLong(6),
                            (Integer) rs.getObject(7), (Integer) rs.getObject(8), (Integer) rs.getObject(9),
                            rs.getString(10), rs.getLong(11), rs.getLong(12),
                            nullableDate(rs.getString(13))));
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
                            rs.getString(12), rs.getString(13), rs.getString(14), rs.getInt(15) != 0,
                            rs.getString(16), rs.getInt(17) != 0, rs.getString(18), rs.getString(19)));
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
                        nullableDate(rs.getString(26)), notes.getOrDefault(id, List.of())));
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
    public ExpectedResponse expected(String window, Period.Range range) {
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
                            rs.getInt(14) != 0, nullableLong(rs, 15)));
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
                new ExpectedResponse.Totals(out, in), null);
        });
    }

    /**
     * The headroom of one calendar month over the budget accounts (V2-REVIEW-FIXES-PLAN.md §10). A
     * transfer is internal — and skipped — when both sides are budget accounts; one that crosses the
     * boundary (to savings, to the mortgage) is money in or out. A claimed fact is counted through
     * its commitment's occurrence, never again as spend; a commitment that lands only outside the
     * budget, or only ever moves money between budget accounts, is left out.
     */
    public ExpectedResponse.Headroom headroom(Period.Range month, LocalDate today, java.util.Set<String> budget,
                                             java.util.Set<String> statementBudget) {
        return read(conn -> {
            // Where each commitment lands, and whether it is only ever an internal move.
            Map<String, java.util.Set<String>> landsOn = new java.util.TreeMap<>();
            Map<String, Boolean> internalOnly = new java.util.TreeMap<>();
            try (PreparedStatement ps = conn.prepareStatement(HubSql.COMMITMENT_FACT_ACCOUNTS);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    landsOn.computeIfAbsent(id, k -> new java.util.TreeSet<>()).add(rs.getString(2));
                    boolean internal = rs.getString(3) != null && budget.contains(rs.getString(2))
                        && budget.contains(rs.getString(4));
                    internalOnly.merge(id, internal, Boolean::logicalAnd);
                }
            }
            Map<String, java.util.Set<String>> ruleAccounts = new java.util.TreeMap<>();
            java.util.Set<String> unscoped = new java.util.TreeSet<>();
            try (PreparedStatement ps = conn.prepareStatement(HubSql.COMMITMENT_RULE_ACCOUNTS);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String account = rs.getString(2);
                    if (account == null || account.isBlank()) {
                        unscoped.add(rs.getString(1));
                    } else {
                        ruleAccounts.computeIfAbsent(rs.getString(1), k -> new java.util.TreeSet<>()).add(account);
                    }
                }
            }

            long incomeIn = 0;
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
                        java.util.Set<String> accounts = !unscoped.contains(id) && ruleAccounts.containsKey(id)
                            ? ruleAccounts.get(id) : landsOn.getOrDefault(id, java.util.Set.of());
                        if (Boolean.TRUE.equals(internalOnly.get(id))
                            || (!accounts.isEmpty() && accounts.stream().noneMatch(budget::contains))) {
                            continue;
                        }
                        boolean in = "in".equals(rs.getString(3));
                        Long expected = nullableLong(rs, 5);
                        Long amount = nullableLong(rs, 10);
                        long moved = amount != null ? Math.abs(amount) : 0;
                        long owed = expected == null ? 0 : Math.abs(expected);
                        switch (rs.getString(9)) {
                            case "occurred", "settled", "partial" -> {
                                if (in) {
                                    incomeIn += moved;
                                } else {
                                    committedPaid += moved;
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

            long uncommittedSpend = 0;
            long movedOut = 0;
            long movedIn = 0;
            long unpaired = 0;
            try (PreparedStatement ps = conn.prepareStatement(HubSql.HEADROOM_ROWS)) {
                ps.setString(1, month.from().toString());
                ps.setString(2, month.to().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String account = rs.getString(1);
                        long amount = rs.getLong(2);
                        if (!budget.contains(account) || "noop".equals(rs.getString(3)) || rs.getString(6) != null) {
                            continue; // outside the budget, not a posting, or counted by its commitment
                        }
                        if ("HELD".equals(rs.getString(4))) {
                            unpaired += Math.abs(amount);
                            continue;
                        }
                        if (rs.getString(5) != null && budget.contains(rs.getString(7))) {
                            continue; // a transfer between two budget accounts moves nothing
                        }
                        if (rs.getString(5) != null && rs.getString(7) != null) {
                            // To or from one of your own accounts outside the budget: moved, not spent.
                            if (amount < 0) {
                                movedOut += -amount;
                            } else {
                                movedIn += amount;
                            }
                            continue;
                        }
                        if (amount < 0) {
                            uncommittedSpend += -amount;
                        } else {
                            incomeIn += amount;
                        }
                    }
                }
            }

            LocalDate through = null;
            try (PreparedStatement ps = conn.prepareStatement(HubSql.ACCOUNT_FRONTIERS)) {
                ps.setString(1, today.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (statementBudget.contains(rs.getString(1))) {
                            LocalDate frontier = LocalDate.parse(rs.getString(2));
                            if (through == null || frontier.isBefore(through)) {
                                through = frontier;
                            }
                        }
                    }
                }
            }
            long left = incomeIn + incomeDue + movedIn - committedPaid - committedDue - uncommittedSpend - movedOut;
            return new ExpectedResponse.Headroom(month.from(), left, incomeIn, incomeDue, committedPaid,
                committedDue, uncommittedSpend, movedOut, movedIn, missed, unpaired, through,
                List.copyOf(new java.util.TreeSet<>(budget)));
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
                        rs.getString(7), rs.getString(8), Instant.parse(rs.getString(9))));
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
                    return new CommitmentRef(declared, !rs.wasNull());
                }
            }
        });
    }

    /** The two faces a commitment precheck asks about (§2.6). */
    public record CommitmentRef(boolean declared, boolean retired) {}

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
