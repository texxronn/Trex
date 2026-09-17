package trex.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Gatherer;

/** Occurrence index per batch. SPEC §2.5. */
public final class Occurrence {

    private Occurrence() {}

    /**
     * Identical-{@link Sig} content-hash candidates get stable ascending occ in input order.
     * Natural-key candidates get occ = 0 and do not advance any counter.
     */
    public static List<OccCandidate> assignOcc(List<Candidate> batch) {
        return batch.stream().gather(occGatherer()).toList();
    }

    public static Gatherer<Candidate, ?, OccCandidate> occGatherer() {
        return Gatherer.<Candidate, Map<Sig, Integer>, OccCandidate>ofSequential(
            HashMap::new,
            (counts, c, downstream) -> {
                if (c.hasReceipt()) {
                    return downstream.push(new OccCandidate(c, 0));
                }
                int occ = counts.merge(Sig.of(c), 1, Integer::sum) - 1;
                return downstream.push(new OccCandidate(c, occ));
            });
    }
}
