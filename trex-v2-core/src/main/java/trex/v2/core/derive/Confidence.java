package trex.v2.core.derive;

/**
 * A transfer's confidence (V2-PROPOSAL.md §9.9.C, §7.2): {@code EXACT} (T1, shared receipt),
 * {@code HIGH} (T2/T3, amount and date and stem), {@code MANUAL} (a {@code PAIR} decision).
 */
public enum Confidence {
    EXACT,
    HIGH,
    MANUAL
}
