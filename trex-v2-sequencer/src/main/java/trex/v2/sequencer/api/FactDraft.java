package trex.v2.sequencer.api;

import trex.v2.core.Observation;
import trex.v2.core.Provenance;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A draft observation from a source adapter (V2-PROPOSAL.md §6.5). The client supplies what the
 * source said; {@code occ} and {@code externalId} are assigned by the sequencer, because they are
 * identity and must not be chosen by a caller.
 *
 * <p>{@code amount} and {@code balance} are boxed so a missing field is a validation error rather
 * than a silent zero.
 */
public record FactDraft(
    String accountRef,
    LocalDate date,
    Long amount,
    Long balance,
    String rawDescription,
    String receipt,
    Observation observation,
    String sourceType,
    Provenance provenance,
    String evidenceId,
    String parser,
    Instant ingestedAt
) {}
