package trex.v2.core;

/**
 * One line of the v2 log, discriminated on {@code kind} (V2-PROPOSAL.md §6). The codec lives in
 * {@code trex.v2.log} so the model stays free of Jackson.
 */
public sealed interface LogLine permits Fact, Decision {

    long n();

    /** {@code "fact"} or {@code "decision"} — the wire discriminator. */
    String kind();
}
