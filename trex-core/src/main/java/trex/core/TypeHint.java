package trex.core;

public enum TypeHint {
    WITHDRAWAL, DEPOSIT, TRANSFER,

    /**
     * A stated balance, not a movement: {@code amount = 0}, {@code balance} authoritative, valid
     * only on a {@link BalanceSource#DECLARED} account. SPEC §2.3.
     * <p>
     * A line kind rather than a field, because declarations arrive on their own schedule and
     * {@code 0} cannot mean "unknown" — $0 is a legitimate attestation, you spent your last cash.
     * Named for a claim rather than a count: the other values name movements, and this one names
     * something that can be recorded and reported but never verified (§5.9).
     */
    ATTESTATION
}
