package trex.v2.core.derive;

import java.time.LocalDate;

/**
 * A projectable unit (V2-PROPOSAL.md §9.9.G, §11): a transfer row, or a current posted fact whose
 * pairing state is {@code EXTERNAL}. An ATTESTATION is never a unit — it would post a $0
 * transaction forever.
 */
public record Unit(String unitId, String unitKind, String accountRef, LocalDate date, long amount,
                   String currency, String category, CategoryOrigin origin, LegState pairing,
                   boolean retired, boolean ineffective) {

    public static final String KIND_TRANSFER = "TRANSFER";
    public static final String KIND_EXTERNAL = "EXTERNAL";
}
