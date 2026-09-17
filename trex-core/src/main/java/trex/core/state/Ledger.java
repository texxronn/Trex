package trex.core.state;

import trex.core.CanonicalEvent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory fold of the journal. The latest line (highest n) per externalId is the
 * authoritative current state. Mutated only by the single writer. SPEC §3.2.
 */
public final class Ledger {

    private final Map<String, CanonicalEvent> firstLine = new HashMap<>();
    private final Map<String, CanonicalEvent> latest = new LinkedHashMap<>();
    private final Map<String, CanonicalEvent> held = new LinkedHashMap<>();
    private long highWaterN;
    private long headOffset;

    /** Fold one journal line, in journal order. */
    public void apply(CanonicalEvent line) {
        if (line.n() <= highWaterN) {
            throw new IllegalStateException("n not strictly increasing: " + line.n() + " after " + highWaterN);
        }
        firstLine.putIfAbsent(line.externalId(), line);
        latest.remove(line.externalId());   // keep insertion order = order of latest n
        latest.put(line.externalId(), line);
        held.remove(line.externalId());
        if (line.state() == trex.core.EventState.HELD) {
            held.put(line.externalId(), line);
        }
        highWaterN = line.n();
    }

    public void setHeadOffset(long headOffset) {
        this.headOffset = headOffset;
    }

    public CanonicalEvent firstLine(String externalId) {
        return firstLine.get(externalId);
    }

    public CanonicalEvent latest(String externalId) {
        return latest.get(externalId);
    }

    public boolean contains(String externalId) {
        return firstLine.containsKey(externalId);
    }

    /** Current HELD lines, in order of their latest n. */
    public java.util.Collection<CanonicalEvent> heldLines() {
        return java.util.Collections.unmodifiableCollection(held.values());
    }

    public long highWaterN() {
        return highWaterN;
    }

    /** Immutable snapshot for lock-free readers. */
    public LedgerView snapshot() {
        return new LedgerView(Map.copyOf(firstLine), latest.values().stream().toList(), highWaterN, headOffset);
    }
}
