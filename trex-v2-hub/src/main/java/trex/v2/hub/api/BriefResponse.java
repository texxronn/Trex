package trex.v2.hub.api;

import java.util.List;

/**
 * A compact snapshot for the family assistant (V2-ASSISTANT-PLAN.md §1 A4, §3 Stage B): one small
 * view over the reads a chat turn would otherwise pull one by one. It is a <b>view</b> — the same
 * numbers the hub already derives, reduced to a handful of scalars and short lists. It stores
 * nothing and computes no new semantics.
 */
public record BriefResponse(long asOfN, List<ReviewCount> review, ReconcileSummary reconcile,
                            ExpectedSummary expected, IngestSummary lastIngest) {

    /** Open review items by kind: the review queue's counts, without the rows behind them. */
    public record ReviewCount(String kind, long count) {}

    /** The reconciliation verdict and the accounts it could not balance. */
    public record ReconcileSummary(boolean ok, List<Gap> gaps) {}

    /** One account whose reconciliation left a gap; the number is what moved unrecorded. */
    public record Gap(String accountRef, long gap) {}

    /** The current calendar month's committed totals and the arrears backlog count. */
    public record ExpectedSummary(String month, long committedOut, long committedIn, int arrears) {}

    /** The newest ingest batch's identity and its appended/duplicate/flagged row counts. */
    public record IngestSummary(String batch, long completedMs, Integer appended, Integer duplicate,
                                Integer flagged) {}
}
