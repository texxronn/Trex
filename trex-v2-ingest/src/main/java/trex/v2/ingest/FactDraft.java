package trex.v2.ingest;

import trex.v2.core.Observation;
import trex.v2.core.Provenance;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One draft observation (V2-PROPOSAL.md §6.5). The client supplies what the source said; the
 * sequencer assigns {@code occ} and {@code externalId}. The shape matches the sequencer's
 * {@code POST /facts} body by field name.
 */
public record FactDraft(String accountRef, LocalDate date, Long amount, Long balance,
                        String rawDescription, String receipt, Observation observation,
                        String sourceType, Provenance provenance, String evidenceId, String parser,
                        Instant ingestedAt) {

    /** The same draft, stamped with the evidence it came from and the parser that read it. */
    public FactDraft withEvidence(String evidenceId, String parser, Instant ingestedAt) {
        return new FactDraft(accountRef, date, amount, balance, rawDescription, receipt, observation,
            sourceType, provenance, evidenceId, parser, ingestedAt);
    }
}
