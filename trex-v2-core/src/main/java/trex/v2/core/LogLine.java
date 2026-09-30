package trex.v2.core;

/**
 * One line of the v2 log, discriminated on the envelope's namespaced {@code kind}
 * (V2-PROPOSAL.md §6). The codec lives in {@code trex.v2.log} so the model stays free of Jackson.
 * A reader that does not know a kind sees {@link Unknown} and ignores it.
 */
public sealed interface LogLine permits Fact, Decision, IngestEvent, Unknown {

    Envelope envelope();

    default long n() {
        return envelope().n();
    }

    /** The namespaced wire discriminator, e.g. {@code trex.fact}. */
    default String kind() {
        return envelope().kind();
    }
}
