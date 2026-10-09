package trex.v2.egress.firefly;

import trex.v2.core.Clean;
import trex.v2.core.MerchantStem;
import trex.v2.egress.hub.HubClient.HubUnit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One resolved unit, as Firefly wants it (V2-PROPOSAL.md §11.2). Pure: a unit plus its category in,
 * a JSON-shaped map out. No network, no clock, no state.
 *
 * <p><b>The transaction type comes from the two accounts, not from trex's classification.</b>
 * Firefly 6.7.3 refuses a {@code transfer} that crosses the asset/liability line — 21 of the 30
 * transfers in the real journal — so the mapped account kinds decide:
 * <pre>
 *   asset     -> asset          transfer
 *   liability -> liability      transfer
 *   asset     -> liability      withdrawal   (paying a card or a loan)
 *   liability -> asset          deposit      (drawing a loan, a card refund)
 * </pre>
 */
public final class Projection {

    /** Our own marks on a Firefly transaction, so a rebuild can recover what we projected. */
    public static final String TAG = "trex";
    public static final String CATEGORY_TAG_PREFIX = "trex-category:";

    private Projection() {}

    public record Posting(String unitId, Map<String, Object> body) {}

    public static Posting of(HubUnit unit, String configRevision, AccountMap accounts) {
        AccountMap.Entry own = require(accounts, unit.accountRef(), unit);
        Map<String, Object> split = new LinkedHashMap<>();

        if ("TRANSFER".equals(unit.unitKind())) {
            AccountMap.Entry other = require(accounts, unit.toAccountRef(), unit);
            split.put("type", transferType(own.kind(), other.kind()));
            split.put("source_id", own.id());
            split.put("destination_id", other.id());
        } else if (unit.amount() < 0) {
            split.put("type", "withdrawal");
            split.put("source_id", own.id());
            split.put("destination_name", counterparty(unit));
        } else {
            split.put("type", "deposit");
            split.put("source_name", counterparty(unit));
            split.put("destination_id", own.id());
        }

        split.put("date", unit.date().toString());
        split.put("amount", amount(unit.amount()));
        split.put("currency_code", unit.currency());
        split.put("description", description(unit));
        split.put("external_id", unit.unitId());
        split.put("internal_reference", unit.accountRef());
        // Both, deliberately: the category field is what Firefly reports on; the tag is our stamp and
        // is what a rebuild reads back to learn what was last projected (§11.6).
        split.put("category_name", unit.category());
        split.put("tags", List.of(TAG, CATEGORY_TAG_PREFIX + unit.category()));
        split.put("notes", notes(unit, configRevision));

        Map<String, Object> body = new LinkedHashMap<>();
        // Firefly's own duplicate hash, under our dedup rather than instead of it. A rejection names
        // the existing group, which is how a lost projection state recovers without a search.
        //
        // It scans the existing transactions, so a first bulk apply slows as the table grows:
        // measured on 6.7.3/SQLite, ~2.8/s over the first hundred falling to ~0.6/s past a
        // thousand (1775 creates in one pass). The runner's 30-minute cap therefore interrupts a
        // large first apply; that is safe, because every apply is idempotent (external_id, then
        // this hash) and a re-plan shows only the remainder. Steady-state applies stay small.
        body.put("error_if_duplicate_hash", true);
        // trex is the single classifier: Firefly's rules must not fight the category we just set.
        body.put("apply_rules", false);
        body.put("transactions", List.of(split));
        return new Posting(unit.unitId(), body);
    }

    /** Same class of account on both sides, or Firefly refuses it. */
    static String transferType(AccountMap.Kind from, AccountMap.Kind to) {
        if (from == to) {
            return "transfer";
        }
        return from == AccountMap.Kind.ASSET ? "withdrawal" : "deposit";
    }

    /**
     * The counterparty's merchant stem — the same stem the worklist groups by, so Firefly's
     * auto-created expense accounts and trex's merchant list cannot drift apart.
     */
    private static String counterparty(HubUnit unit) {
        String stem = MerchantStem.stem(unit.rawDescription());
        return stem.isBlank() ? "(unknown)" : stem;
    }

    private static String description(HubUnit unit) {
        String cleaned = Clean.clean(unit.rawDescription());
        return cleaned.isBlank() ? unit.rawDescription() : cleaned;
    }

    /** Everything a rebuild needs that the tag does not carry: the journal {@code n} and the raw text. */
    private static String notes(HubUnit unit, String configRevision) {
        return "trex n=" + unit.n() + " rules=" + (configRevision == null ? "none" : configRevision)
            + "\n" + unit.rawDescription();
    }

    /** Yours stay; ours are replaced. A re-tag that wrote only ours would delete what you added. */
    public static List<String> tags(com.fasterxml.jackson.databind.JsonNode existing, String category) {
        List<String> out = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode t : existing) {
            String v = t.asText();
            if (!v.equals(TAG) && !v.startsWith(CATEGORY_TAG_PREFIX)) {
                out.add(v);
            }
        }
        out.add(TAG);
        out.add(CATEGORY_TAG_PREFIX + category);
        return out;
    }

    /** Like {@link #amount} but keeps the sign — an opening balance may legitimately be a debt. */
    static String signedAmount(long cents) {
        return (cents < 0 ? "-" : "") + amount(cents);
    }

    /** Cents to a fixed 2-decimal string; never a float, never locale-formatted. */
    static String amount(long cents) {
        return BigDecimal.valueOf(Math.abs(cents), 2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static AccountMap.Entry require(AccountMap accounts, String ref, HubUnit unit) {
        AccountMap.Entry e = accounts.get(ref);
        if (e == null) {
            throw new IllegalStateException(
                "no Firefly mapping for account '" + ref + "' (unit " + unit.unitId() + ")");
        }
        if (e.id() == null) {
            throw new IllegalStateException("account '" + ref + "' maps to Firefly account \""
                + e.name() + "\", which the instance does not have");
        }
        return e;
    }
}
