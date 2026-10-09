package trex.v2.core.derive;

import java.util.List;

/**
 * The matcher's result (V2-COMMITMENTS-PLAN.md §2.5, §2.9; Stage 2): the occurrence rows, the
 * per-commitment arrears, and the fact → commitment bindings, all ordered deterministically
 * (occurrences by commitment id then due date; arrears by commitment id; bindings by fact id) so
 * the same inputs and {@code asOf} always produce the same lists and the same content hashes.
 *
 * <p>{@code facts} is the reverse map (V2-COMMITMENT-FACT-PLAN.md §3.1): one row per claimed fact,
 * including a fact older than the materialised occurrence window. It is a projection of the same
 * claim pass, so it can never disagree with the occurrences about who owns a fact.
 */
public record CommitmentMatch(List<CommitmentOccurrence> occurrences, List<CommitmentArrears> arrears,
                              List<CommitmentFact> facts) {

    public CommitmentMatch {
        occurrences = List.copyOf(occurrences);
        arrears = List.copyOf(arrears);
        facts = List.copyOf(facts);
    }
}
