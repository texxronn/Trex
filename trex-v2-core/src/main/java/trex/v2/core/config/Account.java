package trex.v2.core.config;

/**
 * One account in {@code accounts.yaml} (V2-PROPOSAL.md §6.1, §9.9.D). The ref is part of identity
 * for receipt-keyed rows, so renaming one re-mints ids; accounts are never auto-created.
 *
 * <p>{@code settlementWindowDays} is how long a pending observation may wait before it is stale
 * (§9.9.D); default 7, applied by the loader.
 */
public record Account(String ref, String currency, BalanceSource balanceSource, int settlementWindowDays) {

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
    }
}
