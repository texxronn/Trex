package trex.v2.hub.api;

import java.util.Map;

/**
 * The status strip (V2-PROPOSAL.md §14): {@code n}, index lag, table counts, open review by kind,
 * and the three revision stamps. Firefly drift and reconciliation join it as those stages land.
 */
public record StatusResponse(long n, long offset, long journalHead, long lagBytes,
                             Map<String, Long> counts, Map<String, Long> reviewByKind,
                             String configRevision, String deriveVersion, String hashVersion) {}
