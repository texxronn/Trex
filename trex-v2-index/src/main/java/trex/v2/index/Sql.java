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
        + "raw_description, receipt, occ, observation, source_type, provenance, evidence_id, parser, ingested_at) "
        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    static final String SELECT_FACTS = "SELECT n, external_id, account_ref, date, amount, balance, "
        + "raw_description, receipt, occ, observation, source_type, provenance, evidence_id, parser, ingested_at "
        + "FROM fact ORDER BY n";
    static final String SELECT_FACTS_UPTO = "SELECT n, external_id, account_ref, date, amount, balance, "
        + "raw_description, receipt, occ, observation, source_type, provenance, evidence_id, parser, ingested_at "
        + "FROM fact WHERE n <= ? ORDER BY n";

    // ---- mirror: decision -------------------------------------------------------------------

    static final String INSERT_DECISION = "INSERT INTO decision(n, action, payload, actor, user_id, at) "
        + "VALUES(?,?,?,?,?,?)";
    static final String SELECT_DECISIONS = "SELECT payload FROM decision ORDER BY n";
    static final String SELECT_DECISIONS_UPTO = "SELECT payload FROM decision WHERE n <= ? ORDER BY n";

    // ---- level 2: dropped and rebuilt wholesale --------------------------------------------

    static final List<String> DERIVED_TABLES = List.of(
        "supersession", "chain_resolved", "txn_current", "transfer", "pending", "review_item",
        "category_current", "pin_current", "ineffective_decision", "unit", "user_ack");

    /** Mirror and derived tables, for counts and verification. */
    static final List<String> ALL_TABLES = List.of(
        "fact", "decision", "supersession", "chain_resolved", "txn_current", "transfer", "pending",
        "review_item", "category_current", "pin_current", "ineffective_decision", "unit", "user_ack");

    static final String INSERT_SUPERSESSION = "INSERT INTO supersession(from_id, to_id, decision_n, reason) "
        + "VALUES(?,?,?,?)";
    static final String INSERT_CHAIN_RESOLVED = "INSERT INTO chain_resolved(id, current_id) VALUES(?,?)";
    static final String INSERT_TXN_CURRENT = "INSERT INTO txn_current(external_id, n, account_ref, date, amount, "
        + "balance, raw_description, receipt, occ, observation, source_type, provenance, evidence_id, parser, "
        + "ingested_at, leg, transfer_id, category, category_origin, rule_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    static final String INSERT_TRANSFER = "INSERT INTO transfer(transfer_id, from_leg, to_leg, confidence, origin, "
        + "decision_n, matched_at) VALUES(?,?,?,?,?,?,?)";
    static final String INSERT_PENDING = "INSERT INTO pending(external_id, fact_n, account_ref, date, amount, "
        + "settled_by, state) VALUES(?,?,?,?,?,?,?)";
    static final String INSERT_REVIEW_ITEM = "INSERT INTO review_item(subject, kind, detail, amount_stake, "
        + "opened_at, state_hash) VALUES(?,?,?,?,?,?)";
    static final String INSERT_CATEGORY_CURRENT = "INSERT INTO category_current(external_id, category, origin, "
        + "rule_id) VALUES(?,?,?,?)";
    static final String INSERT_PIN_CURRENT = "INSERT INTO pin_current(external_id, category, decision_n, user_id, "
        + "comment) VALUES(?,?,?,?,?)";
    static final String INSERT_INEFFECTIVE = "INSERT INTO ineffective_decision(decision_n, action, reason) "
        + "VALUES(?,?,?)";
    static final String INSERT_UNIT = "INSERT INTO unit(unit_id, unit_kind, account_ref, date, amount, currency, "
        + "category, origin, pairing, retired, ineffective) VALUES(?,?,?,?,?,?,?,?,?,?,?)";
    static final String INSERT_USER_ACK = "INSERT INTO user_ack(user_id, period, through_n, state_hash, "
        + "config_revision, derive_version, hash_version, acked_at) VALUES(?,?,?,?,?,?,?,?)";

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
