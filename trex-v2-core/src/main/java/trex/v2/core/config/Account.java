package trex.v2.core.config;

import java.time.LocalDate;

/**
 * One account in {@code accounts.yaml} (V2-PROPOSAL.md §6.1, §6.10, §9.9.D). The ref is part of
 * identity for receipt-keyed rows, so renaming one re-mints ids; accounts are never auto-created.
 *
 * <p>{@code settlementWindowDays} is how long a pending observation may wait before it is stale
 * (§9.9.D); default 7, applied by the loader.
 *
 * <p>A {@link BalanceSource#CLEARING} account is a declared position, not a statement: it holds no
 * facts, and {@code closingBalance} is the value it closed at (cents). Its opening is computed
 * backwards from the movements that matched it, so the derived balance lands on the declared value
 * (§6.10). {@code closedAt} optionally flags movement after the close. Both are null for any other
 * kind.
 */
public record Account(String ref, String currency, BalanceSource balanceSource, int settlementWindowDays,
                      Long closingBalance, LocalDate closedAt) {

    /** The common shape: a statement or declared account with no clearing fields. */
    public Account(String ref, String currency, BalanceSource balanceSource, int settlementWindowDays) {
        this(ref, currency, balanceSource, settlementWindowDays, null, null);
    }

    public Account {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("account ref is required");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("account " + ref + ": currency is required");
        }
        if (balanceSource == null) {
            throw new IllegalArgumentException("account " + ref + ": balanceSource is required");
        }
        if (settlementWindowDays < 0) {
            throw new IllegalArgumentException("account " + ref + ": settlementWindowDays must be >= 0");
        }
        if (balanceSource == BalanceSource.CLEARING) {
            if (closingBalance == null) {
                throw new IllegalArgumentException("account " + ref + ": a clearing account needs closingBalance");
            }
        } else if (closingBalance != null || closedAt != null) {
            throw new IllegalArgumentException("account " + ref
                + ": closingBalance/closedAt are only for balanceSource: clearing");
        }
    }

    public boolean clearing() {
        return balanceSource == BalanceSource.CLEARING;
    }
}
