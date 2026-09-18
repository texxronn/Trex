package trex.sequencer.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.core.BatchStatus;
import trex.core.Candidate;
import trex.core.CandidateResult;
import trex.core.CandidateResult.DroppedDuplicate;
import trex.core.CandidateResult.Flagged;
import trex.core.CandidateResult.Held;
import trex.core.CandidateResult.Rejected;
import trex.core.CandidateResult.Resolved;
import trex.core.CanonicalEvent;
import trex.core.Confidence;
import trex.core.EventState;
import trex.core.Flag;
import trex.core.Ids;
import trex.core.MatchOutcome;
import trex.core.MatchOutcome.AmbiguousTransfer;
import trex.core.MatchOutcome.ExactTransfer;
import trex.core.MatchOutcome.FuzzyTransfer;
import trex.core.MatchOutcome.HeldLeg;
import trex.core.MatchOutcome.NotTransfer;
import trex.core.OccCandidate;
import trex.core.Occurrence;
import trex.core.Provenance;
import trex.core.TypeHint;
import trex.sequencer.journal.Journal;
import trex.core.state.Ledger;
import trex.core.state.Reconciliation;
import trex.core.state.LedgerView;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.function.UnaryOperator;

/**
 * The single writer: ingress pipeline (SPEC §3.3), transfer rules (§3.4) and decisions (§3.5).
 * All mutations run under one lock; readers use the published {@link LedgerView}.
 */
public final class Sequencer {

    private static final Logger log = LoggerFactory.getLogger(Sequencer.class);

    private final Journal journal;
    private final Ledger ledger;
    private final AccountRegistry registry;
    private final Matcher matcher;
    private final Clock clock;
    private final UnaryOperator<String> cleaner;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicReference<LedgerView> view = new AtomicReference<>();

    public Sequencer(Journal journal, Ledger ledger, AccountRegistry registry, TransferRules rules, Clock clock) {
        this(journal, ledger, registry, rules, clock, DescriptionCleaner::clean);
    }

    public Sequencer(Journal journal, Ledger ledger, AccountRegistry registry, TransferRules rules, Clock clock,
                     UnaryOperator<String> cleaner) {
        this.journal = journal;
        this.ledger = ledger;
        this.registry = registry;
        this.matcher = new Matcher(rules);
        this.clock = clock;
        this.cleaner = cleaner;
        this.view.set(ledger.snapshot());
    }

    /** Lock-free read of the last committed state. */
    /**
     * Reconcile every account over the published snapshot (SPEC §3.5, §7 test 6). Read-only:
     * no write lock, nothing appended. The first line per externalId is what the algorithm
     * needs, and the snapshot already holds exactly that.
     */
    public ReconcileReport reconcile() {
        LedgerView snapshot = view();
        ReconcileReport report = ReconcileReport.of(snapshot.highWaterN(), snapshot.headOffset(),
            Reconciliation.reconcile(List.copyOf(snapshot.firstLine().values())));
        // Counts and the verdict only: no amounts in logs (SPEC §1).
        log.info("reconcile at n={}: {} accounts, ok={}", report.n(), report.accounts().size(), report.ok());
        return report;
    }

    public LedgerView view() {
        return view.get();
    }

    // ---------------------------------------------------------------- POST /candidates

    public BatchResponse submitCandidates(boolean allOrNone, List<CandidateInput> inputs) {
        writeLock.lock();
        try {
            return ingest(allOrNone, inputs);
        } finally {
            writeLock.unlock();
        }
    }

    /** Pending per-candidate outcome; new legs are resolved to a result after materialize. */
    private sealed interface Pending {
        record Done(CandidateResult result) implements Pending {}
        record NewLeg(String ref, String externalId) implements Pending {}
    }

    private BatchResponse ingest(boolean allOrNone, List<CandidateInput> inputs) {
        List<Pending> pending = new ArrayList<>();
        List<Candidate> valid = new ArrayList<>();
        List<Integer> validSlots = new ArrayList<>();
        boolean anyRejected = false;
        for (int i = 0; i < inputs.size(); i++) {
            CandidateInput in = inputs.get(i);
            String ref = in.ref() != null ? in.ref() : "idx-" + i;
            String reason = in.error() != null ? in.error() : validate(in.candidate());
            if (reason != null) {
                pending.add(new Pending.Done(new Rejected(ref, reason)));
                anyRejected = true;
            } else {
                pending.add(null);
                validSlots.add(i);
                valid.add(in.candidate());
            }
        }
        if (anyRejected && allOrNone) {
            List<CandidateResult> rejects = pending.stream()
                .filter(p -> p instanceof Pending.Done)
                .map(p -> ((Pending.Done) p).result())
                .toList();
            return new BatchResponse(handle(), BatchStatus.REJECTED, rejects);
        }

        BatchWork work = new BatchWork(ledger, clock.instant());
        List<OccCandidate> occ = Occurrence.assignOcc(valid);
        for (int k = 0; k < occ.size(); k++) {
            int slot = validSlots.get(k);
            String ref = inputs.get(slot).ref() != null ? inputs.get(slot).ref() : "idx-" + slot;
            pending.set(slot, ingestOne(ref, occ.get(k), work));
        }

        BatchWork.Materialized m = commit(work);
        List<CandidateResult> results = pending.stream().map(p -> switch (p) {
            case Pending.Done(CandidateResult r) -> r;
            case Pending.NewLeg(String ref, String id) -> newLegResult(ref, id, m, work);
        }).toList();
        String handle = handle();
        log.info("candidate batch {}: {} rows, {} journal lines, outcomes {}",
            handle, inputs.size(), m.lines().size(), outcomes(results));
        return new BatchResponse(handle, anyRejected ? BatchStatus.PARTIAL : BatchStatus.COMMITTED, results);
    }

    private String validate(Candidate c) {
        if (c.accountRef() == null || registry.find(c.accountRef()).isEmpty()) {
            return "unknown accountRef: " + c.accountRef();
        }
        if (c.date() == null) {
            return "missing date";
        }
        if (c.rawDescription() == null) {
            return "missing rawDescription";
        }
        if (c.source() == null || c.source().isBlank()) {
            return "missing source";
        }
        if (c.provenance() == null) {
            return "missing provenance";
        }
        return null;
    }

    private Pending ingestOne(String ref, OccCandidate oc, BatchWork work) {
        Candidate c = oc.c();
        String id = Ids.externalId(Ids.strategyFor(oc));

        CanonicalEvent first = ledger.firstLine(id);
        if (first == null) {
            first = work.newLine(id);
        }
        if (first != null) {
            return new Pending.Done(dedup(ref, id, c, first, work));
        }

        CanonicalEvent line = newLeg(c, id, work);
        MatchOutcome outcome = matcher.match(line, work.heldPool(), work::transferIdTaken);
        switch (outcome) {
            case ExactTransfer(String leg, String contra, String transferId) ->
                collapse(line, contra, transferId, Confidence.EXACT, line.receipt(), work);
            case FuzzyTransfer(String leg, String contra, String transferId) ->
                collapse(line, contra, transferId, Confidence.HIGH, null, work);
            case AmbiguousTransfer a -> work.addNew(withState(line, EventState.REVIEW));
            case HeldLeg h -> work.addNew(withState(line, EventState.HELD));
            case NotTransfer x -> work.addNew(withState(line, EventState.EXTERNAL));
        }
        return new Pending.NewLeg(ref, id);
    }

    /** SPEC §3.3 step 5: same balance → dropped; different → flag the existing transaction once. */
    private CandidateResult dedup(String ref, String id, Candidate c, CanonicalEvent first, BatchWork work) {
        if (first.balance() == c.balance()) {
            return new DroppedDuplicate(ref, id);
        }
        if (!work.current(id).flags().contains(Flag.POTENTIAL_DUP)) {
            work.addReappend(new BatchWork.Reappend(id, null, List.of(Flag.POTENTIAL_DUP), null));
        }
        return new Flagged(ref, id, List.of(Flag.POTENTIAL_DUP));
    }

    private void collapse(CanonicalEvent line, String contraId, String transferId, Confidence confidence,
                          String receipt, BatchWork work) {
        work.addNew(line);
        work.updateNew(line.externalId(), EventState.MATCHED, transferId);
        if (work.newLine(contraId) != null) {
            work.updateNew(contraId, EventState.MATCHED, transferId);
        } else {
            work.addReappend(new BatchWork.Reappend(contraId, EventState.MATCHED, null, null));
        }
        boolean lineIsFrom = line.amount() < 0;
        work.addTransfer(new BatchWork.TransferSpec(
            lineIsFrom ? line.externalId() : contraId,
            lineIsFrom ? contraId : line.externalId(),
            transferId, confidence, Provenance.BANK, receipt, null));
    }

    private CanonicalEvent newLeg(Candidate c, String id, BatchWork work) {
        String currency = registry.find(c.accountRef()).orElseThrow().currency();
        return new CanonicalEvent(0, id, c.accountRef(), null, currency, c.date(), c.amount(), c.balance(),
            cleaner.apply(c.rawDescription()), c.rawDescription(),
            c.amount() < 0 ? TypeHint.WITHDRAWAL : TypeHint.DEPOSIT, null, null, null, EventState.EXTERNAL, null,
            List.of(), c.provenance(), c.source(), c.hasReceipt() ? c.receipt() : null,
            c.counterpartyBsb(), c.counterpartyAcct(), null, null, null, work.ingestedAt());
    }

    private static CanonicalEvent withState(CanonicalEvent l, EventState state) {
        return l.reappend(l.n(), state, l.flags(), l.comment());
    }

    private static CandidateResult newLegResult(String ref, String id, BatchWork.Materialized m, BatchWork work) {
        long n = m.newLegN().get(id);
        return switch (work.newLine(id).state()) {
            case EXTERNAL, MATCHED -> new Resolved(ref, id, n);
            case HELD -> new Held(ref, id);
            case REVIEW -> new Flagged(ref, id, List.of());
        };
    }

    // ---------------------------------------------------------------- POST /decisions

    public BatchResponse submitDecisions(boolean allOrNone, List<DecisionInput> inputs) {
        writeLock.lock();
        try {
            return decide(allOrNone, inputs);
        } finally {
            writeLock.unlock();
        }
    }

    private BatchResponse decide(boolean allOrNone, List<DecisionInput> inputs) {
        BatchWork work = new BatchWork(ledger, clock.instant());
        List<Function<BatchWork.Materialized, CandidateResult>> results = new ArrayList<>();
        List<CandidateResult> rejects = new ArrayList<>();
        Set<String> usedLegs = new HashSet<>();
        boolean anyRejected = false;
        for (int i = 0; i < inputs.size(); i++) {
            DecisionInput d = inputs.get(i);
            String ref = d.decisionRef() != null ? d.decisionRef() : "idx-" + i;
            Optional<String> reject = d.error() != null ? Optional.of(d.error()) : checkDecision(d, work, usedLegs);
            if (reject.isPresent()) {
                Rejected r = new Rejected(ref, reject.get());
                rejects.add(r);
                results.add(m -> r);
                anyRejected = true;
                continue;
            }
            switch (d.action()) {
                case MARK_EXTERNAL -> {
                    int idx = work.addReappend(new BatchWork.Reappend(d.externalId(), EventState.EXTERNAL, null, d.comment()));
                    usedLegs.add(d.externalId());
                    results.add(m -> new Resolved(ref, d.externalId(), m.reappendN().get(idx)));
                }
                case DISMISS_DUP -> {
                    int idx = work.addReappend(new BatchWork.Reappend(d.externalId(), null, List.of(), d.comment()));
                    usedLegs.add(d.externalId());
                    results.add(m -> new Resolved(ref, d.externalId(), m.reappendN().get(idx)));
                }
                case CONFIRM_TRANSFER -> {
                    CanonicalEvent a = work.current(d.legA());
                    CanonicalEvent b = work.current(d.legB());
                    String transferId = Ids.transferId(a.externalId(), b.externalId());
                    work.addReappend(new BatchWork.Reappend(a.externalId(), EventState.MATCHED, null, d.comment()));
                    work.addReappend(new BatchWork.Reappend(b.externalId(), EventState.MATCHED, null, d.comment()));
                    CanonicalEvent from = a.amount() < 0 ? a : b;
                    CanonicalEvent to = a.amount() < 0 ? b : a;
                    int idx = work.addTransfer(new BatchWork.TransferSpec(from.externalId(), to.externalId(),
                        transferId, Confidence.EXACT, Provenance.AUTHORED, null, d.comment()));
                    usedLegs.add(a.externalId());
                    usedLegs.add(b.externalId());
                    results.add(m -> new Resolved(ref, transferId, m.transferN().get(idx)));
                }
            }
        }
        if (anyRejected && allOrNone) {
            log.info("decision batch rejected as all-or-none: {} of {} decisions invalid",
                rejects.size(), inputs.size());
            return new BatchResponse(handle(), BatchStatus.REJECTED, rejects);
        }
        BatchWork.Materialized m = commit(work);
        List<CandidateResult> applied = results.stream().map(f -> f.apply(m)).toList();
        String handle = handle();
        // Decisions are final (SPEC §3.5), so record every one that was accepted.
        log.info("decision batch {}: {} decisions, {} journal lines, outcomes {}",
            handle, inputs.size(), m.lines().size(), outcomes(applied));
        return new BatchResponse(handle, anyRejected ? BatchStatus.PARTIAL : BatchStatus.COMMITTED, applied);
    }

    private static Optional<String> checkDecision(DecisionInput d, BatchWork work, Set<String> usedLegs) {
        if (d.action() == null) {
            return Optional.of("missing action");
        }
        return switch (d.action()) {
            case MARK_EXTERNAL -> {
                CanonicalEvent cur = d.externalId() == null ? null : work.current(d.externalId());
                if (cur == null) {
                    yield Optional.of("unknown externalId: " + d.externalId());
                }
                yield resolvable(cur);
            }
            case DISMISS_DUP -> {
                CanonicalEvent cur = d.externalId() == null ? null : work.current(d.externalId());
                if (cur == null) {
                    yield Optional.of("unknown externalId: " + d.externalId());
                }
                yield cur.flags().contains(Flag.POTENTIAL_DUP)
                    ? Optional.empty()
                    : Optional.of("not flagged POTENTIAL_DUP: " + d.externalId());
            }
            case CONFIRM_TRANSFER -> {
                CanonicalEvent a = d.legA() == null ? null : work.current(d.legA());
                CanonicalEvent b = d.legB() == null ? null : work.current(d.legB());
                if (a == null) {
                    yield Optional.of("unknown externalId: " + d.legA());
                }
                if (b == null) {
                    yield Optional.of("unknown externalId: " + d.legB());
                }
                if (a.externalId().equals(b.externalId())) {
                    yield Optional.of("legA and legB are the same leg");
                }
                if (usedLegs.contains(a.externalId()) || usedLegs.contains(b.externalId())) {
                    yield Optional.of("leg already used by an earlier decision in this request");
                }
                Optional<String> notResolvable = resolvable(a).or(() -> resolvable(b));
                if (notResolvable.isPresent()) {
                    yield notResolvable;
                }
                if (a.accountRef().equals(b.accountRef())) {
                    yield Optional.of("legs are in the same account");
                }
                if (!a.currency().equals(b.currency())) {
                    yield Optional.of("legs have different currencies");
                }
                if (a.amount() == 0 || a.amount() + b.amount() != 0) {
                    yield Optional.of("amounts are not equal and opposite");
                }
                String transferId = Ids.transferId(a.externalId(), b.externalId());
                if (work.transferIdTaken(transferId)) {
                    yield Optional.of("transfer already exists: " + transferId);
                }
                yield Optional.empty();
            }
        };
    }

    private static Optional<String> resolvable(CanonicalEvent cur) {
        return cur.state() == EventState.HELD || cur.state() == EventState.REVIEW
            ? Optional.empty()
            : Optional.of("state is " + cur.state() + ", not HELD/REVIEW: " + cur.externalId());
    }

    // ---------------------------------------------------------------- commit

    /** Append atomically (fsync), then update in-memory state and publish the snapshot. */
    private BatchWork.Materialized commit(BatchWork work) {
        BatchWork.Materialized m = work.materialize();
        if (!m.lines().isEmpty()) {
            long head = journal.appendBatch(m.lines());
            m.lines().forEach(ledger::apply);
            ledger.setHeadOffset(head);
            view.set(ledger.snapshot());
        }
        return m;
    }

    private static String handle() {
        return UUID.randomUUID().toString();
    }

    /**
     * Per-kind counts for a batch log line. Journal lines are financial data, so the
     * log carries counts and ids only — never a description or an amount (SPEC §1).
     */
    private static String outcomes(List<CandidateResult> results) {
        return results.stream()
            .collect(Collectors.groupingBy(r -> r.getClass().getSimpleName(), TreeMap::new, Collectors.counting()))
            .toString();
    }
}
