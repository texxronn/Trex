package trex.v2.core.derive;

/**
 * Where a current fact sits in the transfer question (V2-PROPOSAL.md §9.7, §9.9.C):
 * {@code MATCHED} (part of a structural transfer), {@code HELD} (transfer-shaped, no contra yet)
 * or {@code EXTERNAL} (an ordinary transaction).
 */
public enum LegState {
    MATCHED,
    HELD,
    EXTERNAL
}
