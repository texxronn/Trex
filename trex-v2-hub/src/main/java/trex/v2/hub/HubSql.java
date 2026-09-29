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
               t.leg, t.transfer_id, t.category, t.category_origin, t.rule_id,
               EXISTS(SELECT 1 FROM review_item r WHERE r.subject = t.external_id) AS has_review
        FROM txn_current t""";

    static final String LEDGER_COUNT = "SELECT COUNT(*) FROM txn_current t";

    static final Map<String, String> LEDGER_SORT = Map.of(
        "date", "t.date", "amount", "t.amount", "account", "t.account_ref",
        "category", "t.category", "n", "t.n");

    static final String REVIEW_SELECT = """
        SELECT r.subject, r.kind, r.detail, r.amount_stake, r.opened_at, r.state_hash,
               x.raw_description
        FROM review_item r
        LEFT JOIN (
            SELECT f.external_id, f.raw_description
            FROM fact f
            WHERE f.n = (SELECT MAX(g.n) FROM fact g WHERE g.external_id = f.external_id)
        ) x ON x.external_id = r.subject""";

    static final String REVIEW_ORDER = " ORDER BY r.kind, r.subject";

    static final String TRANSFERS_SELECT = """
        SELECT transfer_id, from_leg, to_leg, confidence, origin, decision_n, matched_at
        FROM transfer ORDER BY transfer_id""";

    static final String UNITS_SELECT = """
        SELECT unit_id, unit_kind, account_ref, date, amount, currency, category, origin, pairing,
               retired, ineffective
        FROM unit ORDER BY date, unit_id""";

    static final String TRANSFER_LEGS = "SELECT transfer_id, from_leg, to_leg FROM transfer";

    static final String CURRENT_FACTS = """
        SELECT n, external_id, account_ref, date, amount, balance, raw_description, receipt, occ,
               observation, source_type, provenance, evidence_id, parser, ingested_at
        FROM txn_current ORDER BY n""";

    static final String PENDING_SELECT = """
        SELECT external_id, account_ref, date, amount, settled_by, state
        FROM pending ORDER BY date, external_id""";

    static final String FACT_KNOWN = "SELECT 1 FROM chain_resolved WHERE id = ?";

    static final String DECISION_KNOWN = "SELECT 1 FROM decision WHERE n = ?";

    static final String LEG_OF = "SELECT leg FROM txn_current WHERE external_id = ?";

    static final String USER_ACK_SELECT = """
        SELECT user_id, external_id, state_hash, config_revision, derive_version, hash_version, acked_at
        FROM user_ack ORDER BY user_id, external_id""";

    static final String ROW_STATE_HASH = "SELECT state_hash FROM txn_current WHERE external_id = ?";

    static final String CURRENT_STATE_HASHES = "SELECT external_id, state_hash FROM txn_current";

    static List<String> reviewKinds() {
        return List.of("POTENTIAL_DUP", "RESTATEMENT", "AMBIGUOUS_TRANSFER", "AMBIGUOUS_SETTLEMENT",
            "UNMATCHED_LEG", "STALE_PENDING", "INEFFECTIVE_DECISION");
    }
}
