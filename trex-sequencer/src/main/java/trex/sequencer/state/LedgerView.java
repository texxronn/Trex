package trex.sequencer.state;

import trex.core.CanonicalEvent;
import trex.core.EventState;
import trex.core.Flag;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable, published snapshot of the ledger. SPEC §3.2 read consistency. */
public record LedgerView(Map<String, CanonicalEvent> firstLine, List<CanonicalEvent> latestLines,
                         long highWaterN, long headOffset) {

    public static final LedgerView EMPTY = new LedgerView(Map.of(), List.of(), 0, 0);

    /** Latest line of every transaction whose current state is HELD, ordered by n. */
    public List<CanonicalEvent> held() {
        return latestLines.stream()
            .filter(e -> e.state() == EventState.HELD)
            .sorted(Comparator.comparingLong(CanonicalEvent::n))
            .toList();
    }

    /** Latest line of every transaction in REVIEW or flagged POTENTIAL_DUP, ordered by n. */
    public List<CanonicalEvent> review() {
        return latestLines.stream()
            .filter(e -> e.state() == EventState.REVIEW || e.flags().contains(Flag.POTENTIAL_DUP))
            .sorted(Comparator.comparingLong(CanonicalEvent::n))
            .toList();
    }

    public Set<String> heldIds() {
        return held().stream().map(CanonicalEvent::externalId).collect(Collectors.toUnmodifiableSet());
    }

    public Set<String> reviewIds() {
        return review().stream().map(CanonicalEvent::externalId).collect(Collectors.toUnmodifiableSet());
    }
}
