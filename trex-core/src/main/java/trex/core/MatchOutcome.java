package trex.core;

import java.util.List;

/** Matcher outcome — sealed so the matcher switch is exhaustive. SPEC §2.3, §3.4. */
public sealed interface MatchOutcome {

    /** T1: shared receipt. */
    record ExactTransfer(String legA, String legB, String transferId) implements MatchOutcome {}

    /** T3: amount/sign/date-window match. */
    record FuzzyTransfer(String legA, String legB, String transferId) implements MatchOutcome {}

    /** More than one contra → REVIEW. */
    record AmbiguousTransfer(String leg, List<String> candidates) implements MatchOutcome {
        public AmbiguousTransfer {
            candidates = List.copyOf(candidates);
        }
    }

    /** Transfer-shaped, no contra yet → HELD. */
    record HeldLeg(String leg) implements MatchOutcome {}

    /** Ordinary → EXTERNAL. */
    record NotTransfer(String leg) implements MatchOutcome {}
}
