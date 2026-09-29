package trex.v2.hub.api;

import java.util.List;

/**
 * What a candidate rule set would change (V2-PROPOSAL.md §9.3): categories moved, transfers gained
 * or lost, review items opened or cleared. Running it twice gives nothing, and applying the
 * candidate produces exactly this diff (§15.6).
 */
public record ReflowPreview(String fromRevision, String toRevision, int categoriesMoved,
                            List<MovedCategory> moved, int transfersAdded, int transfersRemoved,
                            int reviewOpened, int reviewCleared) {

    public record MovedCategory(String externalId, String from, String to) {}
}
