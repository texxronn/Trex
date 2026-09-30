package trex.v2.core;

/**
 * A line whose {@code kind} this build does not know — a newer kind from a newer writer
 * (V2-PROPOSAL.md §6). It carries the envelope and nothing else; every reader ignores it, so an
 * older build never fails on a line a newer one wrote (forward compatibility).
 */
public record Unknown(Envelope envelope) implements LogLine {

    public Unknown {
        Envelope.require(envelope);
    }
}
