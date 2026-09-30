package trex.v2.core;

/**
 * One line of the v2 log, discriminated on the envelope's namespaced {@code kind}
 * (V2-PROPOSAL.md §6). The codec lives in {@code trex.v2.log} so the model stays free of Jackson.
 */
public sealed interface LogLine permits Fact, Decision {

    Envelope envelope();

    default long n() {
        return envelope().n();
    }

    /** The namespaced wire discriminator, e.g. {@code trex.fact}. */
    default String kind() {
        return envelope().kind();
    }
}
