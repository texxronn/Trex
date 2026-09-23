package trex.core;

import java.time.LocalDate;

/** Adapter → sequencer input ("would-be canonical"). SPEC §2.1. */
public record Candidate(
    String candidateRef,      // adapter-local id for correlating the response (e.g. "row-12")
    String accountRef,        // registry key; implies currency + firefly id
    LocalDate date,
    long amount,              // signed cents (×100); -ve = money out
    String rawDescription,    // verbatim
    long balance,             // running balance (cents). PROVENANCE/RECONCILIATION ONLY — never identity, never transaction semantics
    String receipt,           // nullable; ING natural key
    String counterpartyBsb,   // nullable; CDR (phase 2)
    String counterpartyAcct,  // nullable; CDR (phase 2)
    String sourceType,        // kind of input it was read from, e.g. "ing-csv" (= --source-type; copied onto the event)
    Provenance provenance     // BANK for statements; AUTHORED for portal decisions
) {
    /** A blank receipt is treated as absent, so it never becomes a natural key. */
    public boolean hasReceipt() {
        return receipt != null && !receipt.isBlank();
    }
}
