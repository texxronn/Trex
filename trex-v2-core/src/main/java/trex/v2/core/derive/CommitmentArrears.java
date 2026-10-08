package trex.v2.core.derive;

/**
 * The arrears of one tracked commitment at {@code asOf} (V2-COMMITMENTS-PLAN.md §2.9): one row per
 * declared commitment, whether or not it is behind.
 *
 * <p>{@code count} is the number of {@code missed} and {@code partial} occurrences — the backlog;
 * {@code amount} is the expected total still short, signed like the commitment's amounts (a
 * partial contributes only its remainder). {@code lapsed} is the overlay: true when the most
 * recent occurrence whose window has closed is {@code missed} or {@code partial} — the current
 * red state, never the older backlog. An {@code irregular} commitment has no due dates, so it is
 * never lapsed and never carries arrears.
 */
public record CommitmentArrears(String commitmentId, int count, long amount, boolean lapsed) {}
