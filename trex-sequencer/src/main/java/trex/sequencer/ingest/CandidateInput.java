package trex.sequencer.ingest;

import trex.core.Candidate;

/**
 * One bound batch element: either a candidate or a binding error (SPEC §3.3 step 1).
 *
 * @param balanceObserved whether the request actually carried a {@code balance}. It lives here and
 *                        not on {@link Candidate} because Candidate is a Jackson-bound record: a
 *                        twelfth component would arrive absent and bind to {@code false} on every
 *                        ingested row, and a second constructor makes record binding ambiguous —
 *                        both of which this class is free of, never being deserialised itself.
 */
public record CandidateInput(String ref, Candidate candidate, String error, boolean balanceObserved) {

    public static CandidateInput bound(Candidate c) {
        return new CandidateInput(c.candidateRef(), c, null, true);
    }

    public static CandidateInput bound(Candidate c, boolean balanceObserved) {
        return new CandidateInput(c.candidateRef(), c, null, balanceObserved);
    }

    public static CandidateInput unbindable(String ref, String error) {
        return new CandidateInput(ref, null, error, false);
    }
}
