package trex.v2.core.derive;

import java.util.List;

/**
 * The matcher's result (V2-COMMITMENTS-PLAN.md §2.5, §2.9; Stage 2): the occurrence rows and the
 * per-commitment arrears, both ordered deterministically (occurrences by commitment id then due
 * date; arrears by commitment id) so the same inputs and {@code asOf} always produce the same
 * lists and the same content hashes.
 */
public record CommitmentMatch(List<CommitmentOccurrence> occurrences, List<CommitmentArrears> arrears) {

    public CommitmentMatch {
        occurrences = List.copyOf(occurrences);
        arrears = List.copyOf(arrears);
    }
}
