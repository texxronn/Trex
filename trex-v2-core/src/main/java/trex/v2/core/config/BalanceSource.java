package trex.v2.core.config;

/**
 * How an account's balance column is to be read (V2-PROPOSAL.md §6.1, §6.10; SPEC.md §2.3).
 * {@code STATEMENT} accounts reconcile against the bank; {@code DECLARED} accounts (cash) have
 * no statement and are anchored only by ATTESTATION facts; {@code CLEARING} accounts hold no facts
 * at all — a declared position whose opening is computed backwards from the movements that matched
 * it (§6.10).
 */
public enum BalanceSource {
    STATEMENT("statement"),
    DECLARED("declared"),
    CLEARING("clearing");

    private final String wire;

    BalanceSource(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static BalanceSource fromWire(String wire) {
        return switch (wire) {
            case "statement" -> STATEMENT;
            case "declared" -> DECLARED;
            case "clearing" -> CLEARING;
            default -> throw new IllegalArgumentException("unknown balanceSource '" + wire + "'");
        };
    }
}
