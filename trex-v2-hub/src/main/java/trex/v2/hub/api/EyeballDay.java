package trex.v2.hub.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One day of the eyeball walk (V2-PROPOSAL.md §10.3 point 3): the day's transactions, their total,
 * and the closing balance per account. {@code total} excludes internal transfer legs, so a day never
 * counts the same movement twice; {@code closingBalances} is the last row per account in log order.
 */
public record EyeballDay(LocalDate date, long total, Map<String, Long> closingBalances,
                         List<LedgerRow> rows) {}
