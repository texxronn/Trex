package trex.v2.core.config;

import java.time.LocalDate;

/**
 * One account in {@code accounts.yaml} (V2-PROPOSAL.md §6.1, §6.10, §9.9.D). The ref is part of
 * identity for receipt-keyed rows, so renaming one re-mints ids; accounts are never auto-created.
 *
 * <p>{@code settlementWindowDays} is how long a pending observation may wait before it is stale
 * (§9.9.D); default 7, applied by the loader.
 *
 * <p>{@code fetchEveryDays} is the statement-age nudge (QOL_Improvements.md §2): the age beyond
 * which this account's statements count as old. Presentation only, like {@code chipColor} — the
 * loader resolves the default (31 for a statement account, none for declared/clearing) and
 * {@code 0} disables the nudge; null means no cadence. Never identity or logic.
 *
 * <p>A {@link BalanceSource#CLEARING} account is a declared position, not a statement: it holds no
 * facts, and {@code closingBalance} is the value it closed at (cents). Its opening is computed
 * backwards from the movements that matched it, so the derived balance lands on the declared value
 * (§6.10). {@code closedAt} optionally flags movement after the close. Both are null for any other
 * kind.
 *
 * <p>{@code chipColor} is the display colour for the account's chip in the hub UI (a palette name,
 * e.g. {@code blue}); it is presentation only, never identity or logic, and null falls back to a
 * deterministic colour in the UI.
 */
public record Account(String ref, String currency, BalanceSource balanceSource, int settlementWindowDays,
                      Integer fetchEveryDays, Long closingBalance, LocalDate closedAt, String chipColor,
                      boolean budget) {

    /** The common shape: a statement or declared account with no clearing fields and no chip colour. */
    public Account(String ref, String currency, BalanceSource balanceSource, int settlementWindowDays) {
        this(ref, currency, balanceSource, settlementWindowDays, null, null, null, null,
            balanceSource != BalanceSource.CLEARING);
    }

    /** A clearing account; the chip colour is still optional. */
    public Account(String ref, String currency, BalanceSource balanceSource, int settlementWindowDays,
                   Long closingBalance, LocalDate closedAt) {
        this(ref, currency, balanceSource, settlementWindowDays, null, closingBalance, closedAt, null,
            balanceSource != BalanceSource.CLEARING);
    }

    /**
     * Without an explicit {@code budget} flag: every account the money is spent from is in the
     * monthly budget, and a clearing account (no facts of its own) never is.
     */
    public Account(String ref, String currency, BalanceSource balanceSource, int settlementWindowDays,
                   Long closingBalance, LocalDate closedAt, String chipColor) {
        this(ref, currency, balanceSource, settlementWindowDays, null, closingBalance, closedAt, chipColor,
            balanceSource != BalanceSource.CLEARING);
    }

    public Account {
        if (chipColor != null && chipColor.isBlank()) {
            chipColor = null;
        }
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
        if (fetchEveryDays != null && fetchEveryDays < 0) {
            throw new IllegalArgumentException("account " + ref + ": fetchEveryDays must be >= 0");
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
