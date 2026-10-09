package trex.v2.hub.api;

import java.util.Map;

/**
 * The status strip (V2-PROPOSAL.md §14): {@code n}, index lag, table counts, open review by kind,
 * and the three revision stamps. Firefly drift and reconciliation join it as those stages land.
 * {@code through} is the oldest statement frontier among the budget accounts — the date an
 * "all clear" holds to (V2-REVIEW-FIXES-PLAN.md §11); null before any statement.
 */
public record StatusResponse(long n, long offset, long journalHead, long lagBytes,
                             Map<String, Long> counts, Map<String, Long> reviewByKind,
                             String configRevision, String deriveVersion, String hashVersion,
                             java.time.LocalDate through) {}
