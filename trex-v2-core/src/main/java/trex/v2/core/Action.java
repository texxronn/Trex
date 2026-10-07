package trex.v2.core;

/**
 * The complete decision action set (V2-PROPOSAL.md §6.2, §6.7). Small on purpose: anything not
 * here is not in the journal. The wire form is the name itself ({@code "PAIR"} …).
 */
public enum Action {
    PAIR,
    UNPAIR,
    MARK_EXTERNAL,
    SETTLE,
    DISMISS,
    PIN,
    UNPIN,
    SUPERSEDE,
    RETIRE,
    MARK_NOOP,
    UNMARK_NOOP,
    REVOKE,
    USER_ACK,
    USER_UNACK,
    NOTE;

    public String wire() {
        return name();
    }

    public static Action fromWire(String wire) {
        for (Action a : values()) {
            if (a.name().equals(wire)) {
                return a;
            }
        }
        throw new IllegalArgumentException("unknown decision action '" + wire + "'");
    }
}
