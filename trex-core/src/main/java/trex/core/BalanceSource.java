package trex.core;

/**
 * Where an account's observed balances come from. SPEC §0.1, §6.
 * <p>
 * {@code balance} is an observation from <em>outside</em> trex about what an account held — never
 * a figure trex derived. What differs between accounts is not whether such observations exist but
 * <b>how often they arrive</b>, and that is declared per account rather than inferred, because a
 * wrong guess decides whether a gap in the chain reads as a fault or as the normal shape of the
 * data (§0.6: ambiguity goes to review, never to a default).
 */
public enum BalanceSource {

    /**
     * A statement, arriving on every row. The chain is continuous: every movement is accompanied
     * by an observation, {@code Σ amount == closing − opening} must hold, and a break is a fault
     * worth stopping for (§7 test 6).
     */
    STATEMENT,

    /**
     * A person, arriving when they choose (§2.3 {@code ATTESTATION}). The chain is discontinuous
     * by design and the gaps are the interesting output — value that moved and was never recorded.
     * {@code balance} is meaningless on every other line of such an account.
     */
    DECLARED
}
