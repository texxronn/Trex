package trex.v2.hub.api;

import java.util.List;

/**
 * What a candidate {@code transfers.yaml} would change (V2-PROPOSAL.md §9.3, §9.9.C): the legs it
 * would pot or unpot (their matching state moves) and the pairs it would add or remove. Running it
 * twice gives nothing, and applying the candidate produces exactly this diff (§15.6). Writes
 * nothing.
 */
public record TransferPreview(String fromRevision, String toRevision, int pairsAdded, int pairsRemoved,
                              int reviewOpened, int reviewCleared, List<MovedLeg> moved) {

    /** One leg whose matching state would move: {@code from}/{@code to} are {@code null} when absent. */
    public record MovedLeg(String externalId, String from, String to) {}
}
