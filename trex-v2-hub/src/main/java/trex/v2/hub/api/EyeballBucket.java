package trex.v2.hub.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One bucket of the eyeball walk (V2-PROPOSAL.md §10.3 point 3): a day, ISO week or month of
 * transactions, their total, and the closing balance per account. {@code key} is the bucket's
 * identifier ({@code 2026-09-01}, {@code 2026-W39} or {@code 2026-09}). {@code total} excludes
 * internal transfer legs, so a bucket never counts the same movement twice;
 * {@code closingBalances} is the last row per account in log order.
 */
public record EyeballBucket(String key, LocalDate from, LocalDate to, long total,
                            Map<String, Long> closingBalances, List<LedgerRow> rows) {}
