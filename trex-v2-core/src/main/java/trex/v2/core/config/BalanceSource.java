package trex.v2.core.config;

/**
 * How an account's balance column is to be read (V2-PROPOSAL.md §6.1; SPEC.md §2.3).
 * {@code STATEMENT} accounts reconcile against the bank; {@code DECLARED} accounts (cash) have
 * no statement and are anchored only by ATTESTATION facts.
 */
public enum BalanceSource {
    STATEMENT("statement"),
    DECLARED("declared");

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
            default -> throw new IllegalArgumentException("unknown balanceSource '" + wire + "'");
        };
    }
}
