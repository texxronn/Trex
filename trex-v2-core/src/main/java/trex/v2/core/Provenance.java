package trex.v2.core;

/**
 * Where a fact came from (V2-PROPOSAL.md §6.1). A bank published row is {@code BANK}; a person
 * recording cash or stating a balance is {@code AUTHORED} (§12.4). Provenance is not identity.
 */
public enum Provenance {
    BANK("BANK"),
    AUTHORED("AUTHORED");

    private final String wire;

    Provenance(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Provenance fromWire(String wire) {
        return switch (wire) {
            case "BANK" -> BANK;
            case "AUTHORED" -> AUTHORED;
            default -> throw new IllegalArgumentException("unknown provenance '" + wire + "'");
        };
    }
}
