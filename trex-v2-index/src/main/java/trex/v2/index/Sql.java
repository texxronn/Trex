package trex.v2.index;

import java.util.List;

/**
 * Every DML statement the index runs, named by the table it touches (plan §4, coding standards:
 * every SQL statement lives in a .sql file or a named constant with a comment naming its table).
 * No user input is ever concatenated into these.
 */
final class Sql {

    private Sql() {}

    // ---- meta -------------------------------------------------------------------------------

    static final String UPSERT_META = "INSERT INTO meta(key, value) VALUES(?, ?) "
        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value";
    static final String SELECT_META = "SELECT value FROM meta WHERE key = ?";

    // ---- mirror: fact -----------------------------------------------------------------------

    static final String INSERT_FACT = "INSERT INTO fact(n, external_id, account_ref, date, amount, balance, "
        + "raw_description, receipt, occ, observation, source_type, provenance, evidence_id, parser, "
        + "line_v, at_ms, env, source, target) "
        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    static final String SELECT_FACTS = "SELECT n, external_id, account_ref, date, amount, balance, "
        + "raw_description, receipt, occ, observation, source_type, provenance, evidence_id, parser, "
        + "line_v, at_ms, env, source, target "
        + "FROM fact ORDER BY n";
    static final String SELECT_FACTS_UPTO = "SELECT n, external_id, account_ref, date, amount, balance, "
        + "raw_description, receipt, occ, observation, source_type, provenance, evidence_id, parser, "
        + "line_v, at_ms, env, source, target "
        + "FROM fact WHERE n <= ? ORDER BY n";

    // ---- mirror: decision -------------------------------------------------------------------

    static final String INSERT_DECISION = "INSERT INTO decision(n, action, payload, actor, user_id, at) "
        + "VALUES(?,?,?,?,?,?)";
    static final String INSERT_INGEST_EVENT = "INSERT INTO ingest_event(n, phase, batch, evidence_id, file, "
        + "account_ref, source_type, parser, appended, duplicate, flagged, status, line_v, at_ms, env, source, target) "
        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    static final String SELECT_DECISIONS = "SELECT payload FROM decision ORDER BY n";
    static final String SELECT_DECISIONS_UPTO = "SELECT payload FROM decision WHERE n <= ? ORDER BY n";

    // ---- level 2: dropped and rebuilt wholesale --------------------------------------------

    static final List<String> DERIVED_TABLES = List.of(
        "supersession", "chain_resolved", "txn_current", "transfer", "pending", "review_item",
        "category_current", "pin_current", "note_current", "ineffective_decision", "unit", "user_ack",
        "commitment", "commitment_rule", "commitment_occurrence", "commitment_note",
        "commitment_exclusion", "commitment_fact");

    /** Mirror and derived tables, for counts and verification. */
    static final List<String> ALL_TABLES = List.of(
        "fact", "decision", "ingest_event", "supersession", "chain_resolved", "txn_current", "transfer",
        "pending", "review_item", "category_current", "pin_current", "note_current", "ineffective_decision", "unit",
        "user_ack", "commitment", "commitment_rule", "commitment_occurrence", "commitment_note",
        "commitment_exclusion", "commitment_fact");

    static final String INSERT_SUPERSESSION = "INSERT INTO supersession(from_id, to_id, decision_n, reason) "
        + "VALUES(?,?,?,?)";
    static final String INSERT_CHAIN_RESOLVED = "INSERT INTO chain_resolved(id, current_id) VALUES(?,?)";
    /** Resolve a raw id through the supersession chain exactly as derive's map does. */
    static final String SELECT_CHAIN_RESOLVED = "SELECT current_id FROM chain_resolved WHERE id = ?";
    static final String INSERT_TXN_CURRENT = "INSERT INTO txn_current(external_id, n, account_ref, date, amount, "
        + "balance, raw_description, receipt, occ, observation, source_type, provenance, evidence_id, parser, "
        + "line_v, at_ms, env, source, target, role, rail, leg, transfer_id, category, category_origin, rule_id, "
        + "state_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    static final String INSERT_TXN_CLEARING = "INSERT INTO txn_current(external_id, n, account_ref, date, amount, "
        + "balance, raw_description, receipt, occ, observation, source_type, provenance, evidence_id, parser, "
        + "line_v, at_ms, env, source, target, role, rail, leg, transfer_id, category, category_origin, rule_id, "
        + "state_hash, synthetic) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)";
    static final String INSERT_TRANSFER = "INSERT INTO transfer(transfer_id, from_leg, to_leg, confidence, origin, "
        + "decision_n, method, clearing_account, matched_at) VALUES(?,?,?,?,?,?,?,?,?)";
    static final String INSERT_PENDING = "INSERT INTO pending(external_id, fact_n, account_ref, date, amount, "
        + "settled_by, state) VALUES(?,?,?,?,?,?,?)";
    static final String INSERT_REVIEW_ITEM = "INSERT INTO review_item(subject, kind, detail, amount_stake, "
        + "opened_at, state_hash) VALUES(?,?,?,?,?,?)";
    static final String INSERT_CATEGORY_CURRENT = "INSERT INTO category_current(external_id, category, origin, "
        + "rule_id) VALUES(?,?,?,?)";
    static final String INSERT_PIN_CURRENT = "INSERT INTO pin_current(external_id, category, decision_n, user_id, "
        + "comment) VALUES(?,?,?,?,?)";
    static final String INSERT_NOTE_CURRENT = "INSERT INTO note_current(decision_n, external_id, text, user_id, at) "
        + "VALUES(?,?,?,?,?)";
    static final String INSERT_INEFFECTIVE = "INSERT INTO ineffective_decision(decision_n, action, reason) "
        + "VALUES(?,?,?)";
    static final String INSERT_UNIT = "INSERT INTO unit(unit_id, unit_kind, account_ref, date, amount, currency, "
        + "category, origin, pairing, retired, ineffective) VALUES(?,?,?,?,?,?,?,?,?,?,?)";
    static final String INSERT_USER_ACK = "INSERT INTO user_ack(user_id, external_id, state_hash, "
        + "config_revision, derive_version, hash_version, acked_at) VALUES(?,?,?,?,?,?,?)";
    // ---- row-scoped maintenance (incremental refresh §9.4) ----------------------------------
    // The targeted path's DML: keyed by the user_ack primary key (user_id, external_id), where the
    // external_id is already the resolved current id. An ACK upserts (latest effective wins); an
    // UNACK removes the key. Used only when every new line is a USER_ACK/USER_UNACK.
    static final String UPSERT_USER_ACK = "INSERT INTO user_ack(user_id, external_id, state_hash, "
        + "config_revision, derive_version, hash_version, acked_at) VALUES(?,?,?,?,?,?,?) "
        + "ON CONFLICT(user_id, external_id) DO UPDATE SET state_hash=excluded.state_hash, "
        + "config_revision=excluded.config_revision, derive_version=excluded.derive_version, "
        + "hash_version=excluded.hash_version, acked_at=excluded.acked_at";
    static final String DELETE_USER_ACK = "DELETE FROM user_ack WHERE user_id = ? AND external_id = ?";
    // The PIN/UNPIN patch's DML (V2-INCREMENTAL-REFRESH-PLAN.md §5 slice 3). The target must already
    // be a real posting with a unit, so category_current is upserted (keyed by external_id) while
    // txn_current and unit are plain updates; pin_current is upserted on PIN and deleted on UNPIN
    // (latest effective wins, V2-SPEC.md §6.1). The id is already chain-resolved.
    static final String SELECT_TXN_ROW = "SELECT n, external_id, account_ref, date, amount, balance, "
        + "raw_description, receipt, occ, observation, source_type, provenance, evidence_id, parser, "
        + "line_v, at_ms, env, source, target, role, rail, leg, transfer_id, category, category_origin, "
        + "rule_id, state_hash FROM txn_current WHERE external_id = ? AND synthetic = 0";
    static final String SELECT_UNIT_EXISTS = "SELECT 1 FROM unit WHERE unit_id = ?";
    static final String UPSERT_CATEGORY_CURRENT = "INSERT INTO category_current(external_id, category, origin, "
        + "rule_id) VALUES(?,?,?,?) ON CONFLICT(external_id) DO UPDATE SET category=excluded.category, "
        + "origin=excluded.origin, rule_id=excluded.rule_id";
    static final String UPSERT_PIN_CURRENT = "INSERT INTO pin_current(external_id, category, decision_n, "
        + "user_id, comment) VALUES(?,?,?,?,?) ON CONFLICT(external_id) DO UPDATE SET "
        + "category=excluded.category, decision_n=excluded.decision_n, user_id=excluded.user_id, "
        + "comment=excluded.comment";
    static final String DELETE_PIN_CURRENT = "DELETE FROM pin_current WHERE external_id = ?";
    // The NOTE patch (V2-INCREMENTAL-REFRESH-PLAN.md §5 slice 2). A note's decision_n is unique, so
    // the row is a plain insert keyed by that primary key; the external_id is already chain-resolved
    // and echoes derive's INSERT_NOTE_CURRENT (a note accumulates, latest wins is not a thing).
    static final String INSERT_NOTE_CURRENT_SCOPED = "INSERT INTO note_current(decision_n, external_id, "
        + "text, user_id, at) VALUES(?,?,?,?,?)";
    // The DISMISS patch (V2-INCREMENTAL-REFRESH-PLAN.md §5 slice 4). The (kind, subject) pair is the
    // review_item primary key; the subject is resolved exactly as the review stage resolves it for
    // that kind before the delete. A REVOKE stays a full re-derive.
    static final String DELETE_REVIEW_ITEM = "DELETE FROM review_item WHERE kind = ? AND subject = ?";
    // DORMANT_COMMITMENT/COMMITMENT_ARREARS subjects are declared commitment ids, so membership in
    // the declared fold is checked against the derived commitment table (candidates are `detected`).
    static final String SELECT_DECLARED_COMMITMENT = "SELECT 1 FROM commitment WHERE commitment_id = ? "
        + "AND origin = 'declared'";
    static final String UPDATE_TXN_CATEGORY = "UPDATE txn_current SET category=?, category_origin=?, "
        + "rule_id=?, state_hash=? WHERE external_id=?";
    static final String UPDATE_UNIT_CATEGORY = "UPDATE unit SET category=?, origin=? WHERE unit_id=?";
    static final String INSERT_COMMITMENT = "INSERT INTO commitment(commitment_id, candidate_key, name, origin, direction, "
        + "cadence, amount_kind, kind, status, first_date, last_date, anchor_date, current_amount, "
        + "previous_amount, change_pct, change_date, occurrence_count, outliers, regularity, variable, arrears_count, "
        + "arrears_amount, declared_n, retired_n, ended_at, state_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    static final String INSERT_COMMITMENT_RULE = "INSERT INTO commitment_rule(commitment_id, match, "
        + "account_ref, decision_n) VALUES(?,?,?,?)";
    static final String INSERT_COMMITMENT_OCCURRENCE = "INSERT INTO commitment_occurrence(commitment_id, "
        + "due_date, status, window_start, window_end, matched_external_id, matched_date, matched_by, "
        + "off_schedule, settle_n, amount, state_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
    static final String INSERT_COMMITMENT_NOTE = "INSERT INTO commitment_note(decision_n, commitment_id, "
        + "text, user_id, at) VALUES(?,?,?,?,?)";
    static final String INSERT_COMMITMENT_EXCLUSION = "INSERT INTO commitment_exclusion(commitment_id, "
        + "external_id, decision_n) VALUES(?,?,?)";
    static final String INSERT_COMMITMENT_FACT = "INSERT INTO commitment_fact(external_id, "
        + "commitment_id, matched_by) VALUES(?,?,?)";

    // ---- projection state (V2-PROPOSAL.md §11.6): an accelerator, never wiped by derive -------

    static final String SELECT_PROJECTION = "SELECT unit_id, unit_kind, firefly_group_id, category, "
        + "state_hash, config_revision, derive_version, verified_at FROM projection_state ORDER BY unit_id";
    static final String UPSERT_PROJECTION = "INSERT INTO projection_state(unit_id, unit_kind, "
        + "firefly_group_id, category, state_hash, config_revision, derive_version, verified_at) "
        + "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(unit_id) DO UPDATE SET unit_kind=excluded.unit_kind, "
        + "firefly_group_id=excluded.firefly_group_id, category=excluded.category, "
        + "state_hash=excluded.state_hash, config_revision=excluded.config_revision, "
        + "derive_version=excluded.derive_version, verified_at=excluded.verified_at";
    static final String DELETE_PROJECTION = "DELETE FROM projection_state";

    // ---- evidence and source cursors (V2-PROPOSAL.md §7.2, §12.2) ---------------------------

    static final String UPSERT_EVIDENCE = "INSERT OR IGNORE INTO evidence(sha256, path, bytes, "
        + "media_type, source_type, first_seen) VALUES(?,?,?,?,?,?)";
    static final String SELECT_CURSORS = "SELECT source, cursor FROM source_cursor ORDER BY source";
    static final String UPSERT_CURSOR = "INSERT INTO source_cursor(source, cursor, at) VALUES(?,?,?) "
        + "ON CONFLICT(source) DO UPDATE SET cursor=excluded.cursor, at=excluded.at";

    /** Wipe every derived table before a full re-derive. */
    static String deleteAll(String table) {
        return "DELETE FROM " + table;
    }
}
