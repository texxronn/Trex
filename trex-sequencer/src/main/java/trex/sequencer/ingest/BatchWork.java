package trex.sequencer.ingest;

import trex.core.CanonicalEvent;
import trex.core.Confidence;
import trex.core.EventState;
import trex.core.Flag;
import trex.core.Provenance;
import trex.core.TypeHint;
import trex.core.state.Ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pending lines of one write batch, built against the (unchanged) ledger and materialized in
 * SPEC §3.3 step 7 order: new legs, then re-appended versions, then TRANSFER lines.
 * Lines carry n = 0 until {@link #materialize}.
 */
final class BatchWork {

    /** A re-append: null state/flags mean "keep the previous version's value". */
    record Reappend(String externalId, EventState state, List<Flag> flags, String comment) {}

    record TransferSpec(String fromLeg, String toLeg, String transferId, Confidence confidence,
                        Provenance provenance, String receipt, String comment) {}

    /** Materialized lines plus the n assigned to each pending item. */
    record Materialized(List<CanonicalEvent> lines, Map<String, Long> newLegN, List<Long> reappendN, List<Long> transferN) {}

    private final Ledger ledger;
    private final Instant ingestedAt;
    private final LinkedHashMap<String, CanonicalEvent> newLines = new LinkedHashMap<>();
    private final List<Reappend> reappends = new ArrayList<>();
    private final List<TransferSpec> transfers = new ArrayList<>();
    private final Set<String> transferIds = new HashSet<>();
    private final Map<String, CanonicalEvent> working = new HashMap<>();

    BatchWork(Ledger ledger, Instant ingestedAt) {
        this.ledger = ledger;
        this.ingestedAt = ingestedAt;
    }

    /** Latest pending version of a transaction, or the ledger's latest, or null if unknown. */
    CanonicalEvent current(String externalId) {
        CanonicalEvent pending = working.get(externalId);
        return pending != null ? pending : ledger.latest(externalId);
    }

    CanonicalEvent newLine(String externalId) {
        return newLines.get(externalId);
    }

    void addNew(CanonicalEvent line) {
        newLines.put(line.externalId(), line);
        recomputeWorking(line.externalId());
    }

    /** Change a new leg's first-written state (a leg matched within its own batch is written MATCHED directly). */
    void updateNew(String externalId, EventState state, String transferKey) {
        CanonicalEvent l = newLines.get(externalId);
        newLines.put(externalId, new CanonicalEvent(l.n(), l.externalId(), l.accountRef(), l.toAccountRef(),
            l.currency(), l.date(), l.amount(), l.balance(), l.description(), l.rawDescription(), l.typeHint(),
            transferKey, l.legIds(), l.corrects(), state, l.confidence(), l.flags(), l.provenance(), l.sourceType(),
            l.receipt(), l.counterpartyBsb(), l.counterpartyAcct(), l.foreignAmount(), l.foreignCurrency(),
            l.comment(), l.ingestedAt()));
        recomputeWorking(externalId);
    }

    /** @return index of this re-append */
    int addReappend(Reappend r) {
        reappends.add(r);
        working.put(r.externalId(), apply(current(r.externalId()), r));
        return reappends.size() - 1;
    }

    /** @return index of this transfer */
    int addTransfer(TransferSpec t) {
        transfers.add(t);
        transferIds.add(t.transferId());
        return transfers.size() - 1;
    }

    boolean transferIdTaken(String transferId) {
        return transferIds.contains(transferId) || ledger.contains(transferId) || newLines.containsKey(transferId);
    }

    /** HELD legs usable as contras: existing HELD legs still HELD, then new legs HELD so far. */
    List<CanonicalEvent> heldPool() {
        List<CanonicalEvent> pool = new ArrayList<>();
        for (CanonicalEvent l : ledger.heldLines()) {
            CanonicalEvent cur = current(l.externalId());
            if (cur.state() == EventState.HELD) {
                pool.add(cur);
            }
        }
        for (String id : newLines.keySet()) {
            CanonicalEvent cur = current(id);
            if (cur.state() == EventState.HELD) {
                pool.add(cur);
            }
        }
        return pool;
    }

    Instant ingestedAt() {
        return ingestedAt;
    }

    private void recomputeWorking(String externalId) {
        CanonicalEvent v = newLines.get(externalId);
        for (Reappend r : reappends) {
            if (r.externalId().equals(externalId)) {
                v = apply(v, r);
            }
        }
        working.put(externalId, v);
    }

    private static CanonicalEvent apply(CanonicalEvent prev, Reappend r) {
        return prev.reappend(prev.n(),
            r.state() != null ? r.state() : prev.state(),
            r.flags() != null ? r.flags() : prev.flags(),
            r.comment());
    }

    Materialized materialize() {
        long n = ledger.highWaterN();
        List<CanonicalEvent> lines = new ArrayList<>();
        Map<String, CanonicalEvent> written = new HashMap<>();
        Map<String, Long> newLegN = new LinkedHashMap<>();
        for (CanonicalEvent l : newLines.values()) {
            CanonicalEvent line = l.reappend(++n, l.state(), l.flags(), l.comment());
            lines.add(line);
            written.put(line.externalId(), line);
            newLegN.put(line.externalId(), line.n());
        }
        List<Long> reappendN = new ArrayList<>();
        for (Reappend r : reappends) {
            CanonicalEvent prev = written.containsKey(r.externalId()) ? written.get(r.externalId()) : ledger.latest(r.externalId());
            CanonicalEvent next = apply(prev, r);
            CanonicalEvent line = next.reappend(++n, next.state(), next.flags(), next.comment());
            lines.add(line);
            written.put(line.externalId(), line);
            reappendN.add(line.n());
        }
        List<Long> transferN = new ArrayList<>();
        for (TransferSpec t : transfers) {
            CanonicalEvent from = written.getOrDefault(t.fromLeg(), ledger.latest(t.fromLeg()));
            CanonicalEvent to = written.getOrDefault(t.toLeg(), ledger.latest(t.toLeg()));
            CanonicalEvent line = new CanonicalEvent(++n, t.transferId(), from.accountRef(), to.accountRef(),
                from.currency(), from.date(), Math.abs(from.amount()), 0, from.description(), from.rawDescription(),
                TypeHint.TRANSFER, t.transferId(), List.of(from.externalId(), to.externalId()), null,
                EventState.MATCHED, t.confidence(), List.of(), t.provenance(), from.sourceType(), t.receipt(),
                null, null, null, null, t.comment(), ingestedAt);
            lines.add(line);
            transferN.add(line.n());
        }
        return new Materialized(lines, newLegN, reappendN, transferN);
    }
}
