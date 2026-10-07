package trex.v2.core;

/**
 * Who issued a decision (V2-PROPOSAL.md §6.2): a person ({@code user}, with their id), the
 * importer ({@code migrated}, user null), or derivation machinery ({@code system}, user null).
 * Attribution, not authorization (§6.6).
 */
public enum Actor {
    USER("user"),
    MIGRATED("migrated"),
    SYSTEM("system");

    private final String wire;

    Actor(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Actor fromWire(String wire) {
        return switch (wire) {
            case "user" -> USER;
            case "migrated" -> MIGRATED;
            case "system" -> SYSTEM;
            default -> throw new IllegalArgumentException("unknown actor '" + wire + "'");
        };
    }
}
