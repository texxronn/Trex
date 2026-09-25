package trex.egress.firefly;

import trex.core.CanonicalEvent;
import trex.core.EventState;
import trex.core.TypeHint;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One resolved unit, as Firefly wants it. SPEC §5.8.
 * <p>
 * Pure: a line plus its category in, a JSON-shaped map out. No network, no clock, no state — so
 * the mapping is testable without an instance, which matters because every mistake here is silent
 * and lands 1751 times.
 * <p>
 * <b>The transaction type comes from the two accounts, not from trex's classification.</b> That is
 * the correction the dev instance forced: the first draft mapped every TRANSFER line to a Firefly
 * {@code transfer}, and Firefly 6.7.3 rejects 21 of the 30 in the current journal, because a
 * transfer may not cross between an asset and a liability. Measured:
 * <pre>
 *   asset     -> asset        transfer
 *   liability -> liability    transfer
 *   asset     -> liability    withdrawal    (paying a card or a loan)
 *   liability -> asset        deposit       (drawing a loan, a card refund)
 * </pre>
 */
public final class Projection {

    /** Our own marks on a Firefly transaction, so a rebuild can recover what we projected. */
    public static final String TAG = "trex";
    public static final String CATEGORY_TAG_PREFIX = "trex-category:";

    private Projection() {}

    /**
     * A unit ready to post.
     *
     * @param externalId  trex's id — the join that makes the cache disposable
     * @param body        the group-level request body, {@code transactions} included
     */
    public record Posting(String externalId, Map<String, Object> body) {}

    /**
     * Whether a row is a projectable unit at all (§5.8).
     * <p>
     * A TRANSFER line replaces its two legs; the legs stay in the journal for audit and must never
     * be posted, or every internal transfer is counted twice. They are excluded by state rather
     * than by looking them up: a matched leg's state is {@code MATCHED}, never {@code EXTERNAL}.
     * HELD and REVIEW rows are withheld until they are resolved.
     */
    public static boolean projectable(CanonicalEvent line) {
        // An ATTESTATION is trex's own bookkeeping, not a transaction: no money moved, and its
        // state is EXTERNAL like any settled row, so without this it would post as a $0
        // withdrawal. What it records — the difference between what was stated and what was
        // itemised — is a REPORTING fact, and the hledger egress renders it as one (§5.9).
        if (line.typeHint() == TypeHint.ATTESTATION) {
            return false;
        }
        return line.typeHint() == TypeHint.TRANSFER || line.state() == EventState.EXTERNAL;
    }

    /**
     * @param category the category derived by the gateway; never null — an uncategorised row is
     *                 tagged {@code UNCATEGORIZED} rather than left untagged, because a missing tag
     *                 is indistinguishable from an egress that failed
     */
    public static Posting of(CanonicalEvent line, String category, String rulesRevision, AccountMap accounts) {
        AccountMap.Entry own = require(accounts, line.accountRef(), line);
        Map<String, Object> split = new LinkedHashMap<>();

        if (line.typeHint() == TypeHint.TRANSFER) {
            AccountMap.Entry other = require(accounts, line.toAccountRef(), line);
            split.put("type", transferType(own.kind(), other.kind()));
            split.put("source_id", own.id());
            split.put("destination_id", other.id());
        } else if (line.amount() < 0) {
            split.put("type", "withdrawal");
            split.put("source_id", own.id());
            split.put("destination_name", counterparty(line));
        } else {
            split.put("type", "deposit");
            split.put("source_name", counterparty(line));
            split.put("destination_id", own.id());
        }

        split.put("date", line.date().toString());
        split.put("amount", amount(line.amount()));
        split.put("currency_code", line.currency());
        split.put("description", description(line));
        split.put("external_id", line.externalId());
        split.put("internal_reference", line.accountRef());
        // Both, deliberately: the category field is what Firefly reports on, the tag is our stamp
        // and is what a rebuild reads back to learn what was last projected (§5.8).
        split.put("category_name", category);
        split.put("tags", List.of(TAG, CATEGORY_TAG_PREFIX + category));
        split.put("notes", notes(line, rulesRevision));

        Map<String, Object> body = new LinkedHashMap<>();
        // Firefly's own duplicate hash, under our dedup rather than instead of it. A rejection
        // names the existing group, which is how a lost cache recovers without a search.
        body.put("error_if_duplicate_hash", true);
        // trex is the single classifier (§5.6): Firefly's rules would otherwise fight the
        // category we just set, on every re-projection.
        body.put("apply_rules", false);
        body.put("transactions", List.of(split));
        return new Posting(line.externalId(), body);
    }

    /** Same class of account on both sides, or Firefly refuses it. */
    private static String transferType(AccountMap.Kind from, AccountMap.Kind to) {
        if (from == to) {
            return "transfer";
        }
        return from == AccountMap.Kind.ASSET ? "withdrawal" : "deposit";
    }

    /**
     * The counterparty account's name — an expense account for spending, a revenue account for
     * income, either created by Firefly on first use. The merchant stem is used rather than the
     * raw description so one shop is one account: 1703 withdrawals collapse to 442 counterparties.
     * It is the same stem the worklist groups by, from the same class, so Firefly's expense
     * accounts and trex's merchant list cannot drift apart.
     */
    private static String counterparty(CanonicalEvent line) {
        String stem = trex.category.Merchant.stem(line.rawDescription());
        return stem.isBlank() ? "(unknown)" : stem;
    }

    private static String description(CanonicalEvent line) {
        String d = line.description();
        return d == null || d.isBlank() ? line.rawDescription() : d;
    }

    /**
     * Everything a rebuild needs that the tag does not carry. The cache is an accelerator, so
     * {@code n} has to live somewhere durable, and Firefly is the only durable place there is.
     */
    private static String notes(CanonicalEvent line, String rulesRevision) {
        return "trex n=" + line.n() + " rules=" + (rulesRevision == null ? "none" : rulesRevision)
            + "\n" + line.rawDescription();
    }

    /**
     * Cents to a fixed 2-decimal string. Never a float, and never formatted with the default
     * locale — a comma decimal separator would be accepted as a different number.
     */
    /** Like {@link #amount} but keeps the sign — an opening balance may legitimately be a debt. */
    static String signedAmount(long cents) {
        return (cents < 0 ? "-" : "") + amount(cents);
    }

    static String amount(long cents) {
        return BigDecimal.valueOf(Math.abs(cents), 2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static AccountMap.Entry require(AccountMap accounts, String ref, CanonicalEvent line) {
        AccountMap.Entry e = accounts.get(ref);
        if (e == null) {
            throw new IllegalStateException(
                "no Firefly mapping for account '" + ref + "' (transaction " + line.externalId() + ")");
        }
        if (e.id() == null) {
            throw new IllegalStateException(
                "account '" + ref + "' maps to Firefly account \"" + e.name() + "\", which the instance does not have");
        }
        return e;
    }
}
