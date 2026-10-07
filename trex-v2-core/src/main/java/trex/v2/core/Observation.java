package trex.v2.core;

/**
 * What the source published about a row (V2-PROPOSAL.md §6.1).
 *
 * <p>{@code POSTED} is a settled row; {@code PENDING} is an authorisation. A pending fact is
 * never dropped and never counted: it is excluded from current totals, the balance chain and
 * projection, shown in its own lane, and settled or expired by derivation (§9.6, §12.3).
 */
public enum Observation {
    POSTED("posted"),
    PENDING("pending");

    private final String wire;

    Observation(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Observation fromWire(String wire) {
        return switch (wire) {
            case "posted" -> POSTED;
            case "pending" -> PENDING;
            default -> throw new IllegalArgumentException("unknown observation '" + wire + "'");
        };
    }
}
