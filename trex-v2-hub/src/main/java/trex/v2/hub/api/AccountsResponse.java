package trex.v2.hub.api;

import java.time.LocalDate;
import java.util.List;

/**
 * The Accounts overview (V2-PROPOSAL.md §10.1, §10.5): per-account earliest/latest, a facts-derived
 * coverage strip over a window, and the newest ingest for the account. Read-only and recomputed per
 * request; statement periods are not recorded, so a hole is a question to check, not a verdict.
 */
public record AccountsResponse(String window, String granularity, LocalDate from, LocalDate to,
                               List<AccountCoverage> accounts) {

    /**
     * One account row: all-time totals, the window's buckets, and the newest ingest (nullable).
     * {@code opening} is the derived opening (backward for a statement, computed for a clearing
     * account, §6.10/§11.3), or null when the account has no facts and no clearing movements.
     */
    public record AccountCoverage(String ref, String currency, String balanceSource, Long opening,
                                  LocalDate first, LocalDate last, long txns, long txnsInWindow,
                                  long holes, Import lastImport, List<Bucket> buckets) {}

    /** The newest ingest batch for the account: the file, its status, and when it started. */
    public record Import(String file, String status, long startedMs) {}

    /**
     * One strip cell. State: {@code facts} (rows in the bucket), {@code hole} (no rows, inside the
     * account's range), {@code before}/{@code after} (outside that range), {@code none} (the
     * account has no rows at all). {@code files} names the ingest file(s) that put rows there.
     */
    public record Bucket(String key, LocalDate from, LocalDate to, long txns, String state,
                         List<String> files) {}
}
