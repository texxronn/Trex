package trex.v2.hub.api;

import java.util.List;

/**
 * The rows that moved in an acknowledged period (V2-PROPOSAL.md §9.4, §15.7). Computed by deriving
 * the period at the ACK's {@code throughN} and comparing the unit set with the current one, so the
 * attribution is reproducible from the log rather than stored.
 */
public record AckDiff(String user, String period, long throughN, boolean stale, List<String> moved) {}
