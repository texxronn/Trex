package trex.v2.core.derive;

/** The settlement lifecycle of a pending observation (V2-PROPOSAL.md §9.9.D, §12.3). */
public enum PendingState {
    OPEN,
    SETTLED,
    STALE
}
