package trex.sequencer.ingest;

import trex.core.Candidate;

/** One bound batch element: either a candidate or a binding error (SPEC §3.3 step 1). */
public record CandidateInput(String ref, Candidate candidate, String error) {

    public static CandidateInput bound(Candidate c) {
        return new CandidateInput(c.candidateRef(), c, null);
    }

    public static CandidateInput unbindable(String ref, String error) {
        return new CandidateInput(ref, null, error);
    }
}
