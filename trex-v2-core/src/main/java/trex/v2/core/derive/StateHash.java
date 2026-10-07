package trex.v2.core.derive;

import trex.v2.core.Hashes;

/**
 * The content hash of one row (V2-PROPOSAL.md §9.4, §9.5, §9.9.G).
 *
 * <p>It hashes a canonical, ordered serialisation of a single current transaction: its resolved id,
 * role, pairing state, account, date, amount, currency, category, category origin and transfer id.
 * Excluded, by construction: ages and stale badges, display formatting, {@code n} ordering noise,
 * and the revision strings. So a version bump flags a read row as moved only when something
 * actually moved — "you never redo a row that did not move" holds across upgrades too.
 */
public final class StateHash {

    private StateHash() {}

    /** The canonical hash of one current row; {@code currency} comes from the account registry. */
    public static String forRow(CurrentFact c, String currency) {
        return Hashes.sha256(String.join("|",
            c.externalId(),
            c.role() == null ? "" : c.role().name(),
            c.leg() == null ? "" : c.leg().name(),
            c.fact().accountRef(),
            c.fact().date().toString(),
            Long.toString(c.fact().amount()),
            currency == null ? "" : currency,
            c.category() == null ? "" : c.category(),
            c.categoryOrigin() == null ? "" : c.categoryOrigin().name(),
            c.transferId() == null ? "" : c.transferId()));
    }
}
