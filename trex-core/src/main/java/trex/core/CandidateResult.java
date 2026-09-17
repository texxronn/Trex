package trex.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Per-candidate (or per-decision) outcome returned in the batch response. SPEC §2.3.
 * For decisions, {@code candidateRef} carries the decisionRef.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(CandidateResult.Resolved.class),
    @JsonSubTypes.Type(CandidateResult.DroppedDuplicate.class),
    @JsonSubTypes.Type(CandidateResult.Held.class),
    @JsonSubTypes.Type(CandidateResult.Flagged.class),
    @JsonSubTypes.Type(CandidateResult.Rejected.class)
})
public sealed interface CandidateResult {

    String candidateRef();

    record Resolved(String candidateRef, String externalId, long n) implements CandidateResult {}

    record DroppedDuplicate(String candidateRef, String externalId) implements CandidateResult {}

    record Held(String candidateRef, String externalId) implements CandidateResult {}

    record Flagged(String candidateRef, String externalId, List<Flag> flags) implements CandidateResult {
        public Flagged {
            flags = List.copyOf(flags);
        }
    }

    record Rejected(String candidateRef, String reason) implements CandidateResult {}
}
