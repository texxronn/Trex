package trex.v2.core.derive;

/**
 * The arrears of one tracked commitment at {@code asOf} (V2-MANUAL-ARREARS-PLAN.md §3.4): one row
 * per declared commitment, whether or not it is behind.
 *
 * <p>{@code count} is the number of {@code missed} occurrences — the holes, the only automatic
 * arrears; {@code amount} is their expected total at the missed due dates, signed like the
 * commitment's amounts. {@code lapsed} is the overlay: true when the most recent occurrence whose
 * window has closed is {@code missed} — the current red state, never the older backlog. An
 * {@code irregular} commitment has no due dates, so it is never lapsed and never carries arrears.
 */
public record CommitmentArrears(String commitmentId, int count, long amount, boolean lapsed) {}
