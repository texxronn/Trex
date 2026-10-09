package trex.v2.hub;

import java.util.List;
import java.util.Map;

/**
 * Every SQL statement the hub runs, named by the table it reads (plan §4). Filters are bound as
 * parameters; the only concatenated fragments are the whitelisted sort column and direction, and
 * the predicate fragments built from fixed strings.
 */
final class HubSql {

    private HubSql() {}

    static final String LEDGER_SELECT = """
        SELECT t.external_id, t.n, t.account_ref, t.date, t.amount, t.balance, t.raw_description,
               t.leg, t.role, t.rail, t.transfer_id, t.category, t.category_origin, t.rule_id,
               EXISTS(SELECT 1 FROM review_item r WHERE r.subject = t.external_id) AS has_review,
               (SELECT n.text FROM note_current n WHERE n.external_id = t.external_id
                 ORDER BY n.decision_n DESC LIMIT 1) AS latest_note,
               t.synthetic,
               cf.commitment_id,
               cm.name AS commitment_name
        FROM txn_current t
        LEFT JOIN commitment_fact cf ON cf.external_id = t.external_id
        LEFT JOIN commitment cm ON cm.commitment_id = cf.commitment_id""";

    static final String LEDGER_COUNT = "SELECT COUNT(*) FROM txn_current t";

    static final Map<String, String> LEDGER_SORT = Map.of(
        "date", "t.date", "amount", "t.amount", "account", "t.account_ref",
        "category", "t.category", "n", "t.n");

    static final String REVIEW_SELECT = """
        SELECT r.subject, r.kind, r.detail, r.amount_stake, r.opened_at, r.state_hash,
               x.raw_description, x.date,
               COALESCE(x.account_ref, CASE WHEN r.kind = 'BALANCE_BREAK' THEN r.subject END) AS account_ref,
               x.amount
        FROM review_item r
        LEFT JOIN (
            SELECT f.external_id, f.raw_description, f.date, f.account_ref, f.amount
            FROM fact f
            WHERE f.n = (SELECT MAX(g.n) FROM fact g WHERE g.external_id = f.external_id)
        ) x ON x.external_id = r.subject""";

    static final String REVIEW_ORDER = " ORDER BY r.kind, r.subject";

    /** The note thread, oldest first (§6.2 {@code NOTE}); all rows, or one id. */
    static final String NOTES_SELECT =
        "SELECT external_id, text, decision_n, user_id, at FROM note_current ORDER BY decision_n";
    static final String NOTES_FOR_SELECT =
        "SELECT external_id, text, decision_n, user_id, at FROM note_current WHERE external_id = ? "
        + "ORDER BY decision_n";

    /** Effective {@code DISMISS} decisions (not revoked), newest first, with their reason (§9.9.F). */
    static final String DISMISSALS_SELECT = """
        SELECT json_extract(payload, '$.item'), json_extract(payload, '$.externalIds'),
               json_extract(payload, '$.comment'), user_id, at, n
        FROM decision
        WHERE action = 'DISMISS'
          AND n NOT IN (SELECT json_extract(payload, '$.revokes') FROM decision WHERE action = 'REVOKE')
        ORDER BY n DESC""";

    static final String TRANSFERS_SELECT = """
        SELECT transfer_id, from_leg, to_leg, confidence, origin, decision_n, method, clearing_account, matched_at
        FROM transfer ORDER BY transfer_id""";

    // ---- commitments (V2-COMMITMENTS-PLAN.md §2.7, §2.8) -------------------------------------

    /**
     * The candidate behind a {@code SUSPECTED_RECURRING} stem, for the review row's enrichment
     * (§2.8). The caller mints the id with {@code Commitments.candidateId}, the detector's own
     * function, so the join can never disagree with the id the derivation stored.
     */
    static final String COMMITMENT_CANDIDATE = """
        SELECT cadence, first_date, last_date, occurrence_count, current_amount, regularity,
               change_pct
        FROM commitment WHERE commitment_id = ?""";

    /** The registry: every candidate and declared row, ordered by id (deterministic). */
    static final String COMMITMENTS_SELECT = """
        SELECT commitment_id, name, origin, direction, cadence, amount_kind, kind, status,
               first_date, last_date, anchor_date, current_amount, previous_amount, change_pct,
               change_date, occurrence_count, outliers, regularity, variable, arrears_count,
               arrears_amount, declared_n, retired_n, ended_at, candidate_key,
               (SELECT MIN(o.due_date) FROM commitment_occurrence o
                 WHERE o.commitment_id = commitment.commitment_id AND o.status = 'due') AS next_due
        FROM commitment ORDER BY commitment_id""";

    /** The effective rule set (a projection of the latest effective declaration), deterministic. */
    static final String COMMITMENT_RULES_SELECT =
        "SELECT commitment_id, match, account_ref FROM commitment_rule "
        + "ORDER BY commitment_id, decision_n, match, account_ref";

    /** The effective {@code NOTE_COMMITMENT} thread, oldest first, like {@code note_current}. */
    static final String COMMITMENT_NOTES_SELECT =
        "SELECT decision_n, commitment_id, text, user_id, at FROM commitment_note ORDER BY decision_n";

    /**
     * The current facts the commitment stage sees (V2-EXPECTED-UX-PLAN.md §7 Stage 2): synthetic
     * clearing legs are not facts the detector saw, and a {@code noop} row is the one exclusion.
     * The activity read scans it by a candidate's stem or a declared commitment's rules.
     */
    static final String ACTIVITY_FACTS = """
        SELECT external_id, date, account_ref, amount, raw_description
        FROM txn_current WHERE synthetic = 0 AND role != 'noop'
        ORDER BY date, n""";

    /** The registry row's routing fields for an activity read. */
    static final String COMMITMENT_BY_ID =
        "SELECT candidate_key, direction, ended_at FROM commitment WHERE commitment_id = ?";

    /** The facts bound to one declared commitment (the reverse map, V2-COMMITMENT-FACT-PLAN.md §3.3). */
    static final String COMMITMENT_FACTS_FOR = """
        SELECT t.external_id, t.date, t.account_ref, t.amount, t.raw_description, t.n, cf.matched_by
        FROM commitment_fact cf JOIN txn_current t ON t.external_id = cf.external_id
        WHERE cf.commitment_id = ? ORDER BY t.date, t.n""";

    /** The facts the person excluded from one commitment, with the fact data (so Include works). */
    static final String COMMITMENT_EXCLUDED_FACTS_FOR = """
        SELECT t.external_id, t.date, t.account_ref, t.amount, t.raw_description, t.n
        FROM commitment_exclusion ce JOIN txn_current t ON t.external_id = ce.external_id
        WHERE ce.commitment_id = ? ORDER BY t.date, t.n""";

    /** The effective rules of one commitment, in declaration order (the activity scan). */
    static final String COMMITMENT_RULES_FOR = """
        SELECT match, account_ref FROM commitment_rule
        WHERE commitment_id = ? ORDER BY decision_n, match, account_ref""";

    /** The effective exclusions of one commitment (the activity read marks those rows). */
    static final String COMMITMENT_EXCLUSIONS_FOR =
        "SELECT external_id FROM commitment_exclusion WHERE commitment_id = ?";

    /** The window's occurrences joined to their commitment, oldest first (§2.8). */
    static final String EXPECTED_OCCURRENCES = """
        SELECT o.commitment_id, c.name, c.direction, c.cadence, c.current_amount,
               o.due_date, o.window_start, o.window_end, o.status, o.amount,
               o.matched_external_id, o.matched_date, o.matched_by, o.off_schedule, o.settle_n
        FROM commitment_occurrence o
        JOIN commitment c ON c.commitment_id = o.commitment_id
        WHERE o.due_date >= ? AND o.due_date <= ?
        ORDER BY o.due_date, o.commitment_id""";

    /** Every hole (a missed occurrence), oldest first, measured against the current price. */
    static final String ARREARS_OCCURRENCES = """
        SELECT o.commitment_id, c.name, c.direction, c.cadence, c.current_amount,
               o.due_date, o.status, o.amount
        FROM commitment_occurrence o
        JOIN commitment c ON c.commitment_id = o.commitment_id
        WHERE o.status = 'missed'
        ORDER BY o.due_date, o.commitment_id""";

    /** All-time first/last transaction date and the row count, one row per account. */
    static final String ACCOUNT_TOTALS = """
        SELECT account_ref, MIN(date), MAX(date), COUNT(*)
        FROM txn_current GROUP BY account_ref""";

    /**
     * The window's current rows with the ingest file that owns each one (V2-PROPOSAL.md §10.5). A
     * fact sits strictly between its batch's markers, so the range is exclusive; a manual cash fact
     * matches no batch and gets a null file.
     */
    static final String ACCOUNT_COVERAGE = """
        SELECT t.account_ref, t.date, b.file
        FROM txn_current t
        LEFT JOIN ingest_batch b
          ON b.account_ref = t.account_ref AND t.n > b.n_start AND t.n < b.n_end
        WHERE t.date >= ? AND t.date <= ?
        ORDER BY t.account_ref, t.date""";

    /** Every ingest batch, oldest first; the caller keeps the last row per account. */
    static final String ACCOUNT_LAST_IMPORTS = """
        SELECT account_ref, file, status, started_ms FROM ingest_batch ORDER BY started_ms""";

    static final String UNITS_SELECT = """
        SELECT unit_id, unit_kind, account_ref, date, amount, currency, category, origin, pairing,
               retired, ineffective
        FROM unit ORDER BY date, unit_id""";

    static final String TRANSFER_LEGS = "SELECT transfer_id, from_leg, to_leg FROM transfer";

    /** The current posted facts. The chain, the projection and the walk run over transactions only
     * (§6.9); noop rows are read separately for the reconcile result's exclusions. */
    static final String CURRENT_FACTS = """
        SELECT n, external_id, account_ref, date, amount, balance, raw_description, receipt, occ,
               observation, source_type, provenance, evidence_id, parser,
               line_v, at_ms, env, source, target
        FROM txn_current WHERE role = 'transaction' AND synthetic = 0 ORDER BY n""";

    /** The current noop rows, the rows the balance chain skips by role (§6.9). */
    static final String CURRENT_NOOPS = """
        SELECT n, external_id, account_ref, date, amount, balance, raw_description, receipt, occ,
               observation, source_type, provenance, evidence_id, parser,
               line_v, at_ms, env, source, target
        FROM txn_current WHERE role = 'noop' AND synthetic = 0 ORDER BY n""";

    /**
     * The latest effective role decision naming any id in a chain, with its reason (§6.9). A
     * decision naming an earlier id still applies via {@code chain_resolved}; decisions a REVOKE
     * targets are skipped. The action distinguishes a decision-classified noop from the profile
     * default, so a later {@code UNMARK_NOOP} correctly yields the profile's answer.
     */
    static final String ROLE_DECISION = """
        SELECT n, action, json_extract(payload, '$.reason')
        FROM decision
        WHERE action IN ('MARK_NOOP', 'UNMARK_NOOP')
          AND json_extract(payload, '$.externalId') IN (SELECT id FROM chain_resolved WHERE current_id = ?)
          AND n NOT IN (SELECT json_extract(payload, '$.revokes') FROM decision WHERE action = 'REVOKE')
        ORDER BY n DESC LIMIT 1""";

    static final String PENDING_SELECT = """
        SELECT external_id, account_ref, date, amount, settled_by, state
        FROM pending ORDER BY date, external_id""";

    static final String FACT_KNOWN = "SELECT 1 FROM chain_resolved WHERE id = ?";

    static final String DECISION_KNOWN = "SELECT 1 FROM decision WHERE n = ?";

    static final String LEG_OF = "SELECT leg FROM txn_current WHERE external_id = ?";

    /** A commitment's precheck faces (§2.6): declared (origin) and retired (retired_n set). */
    static final String COMMITMENT_REF = "SELECT origin, retired_n FROM commitment WHERE commitment_id = ?";

    static final String USER_ACK_SELECT = """
        SELECT user_id, external_id, state_hash, config_revision, derive_version, hash_version, acked_at
        FROM user_ack ORDER BY user_id, external_id""";

    static final String ROW_STATE_HASH = "SELECT state_hash FROM txn_current WHERE external_id = ?";

    static final String CURRENT_STATE_HASHES = "SELECT external_id, state_hash FROM txn_current";

    static List<String> reviewKinds() {
        return List.of("POTENTIAL_DUP", "RESTATEMENT", "AMBIGUOUS_TRANSFER", "AMBIGUOUS_SETTLEMENT",
            "UNMATCHED_LEG", "STALE_PENDING", "INEFFECTIVE_DECISION", "BALANCE_BREAK");
    }
}
