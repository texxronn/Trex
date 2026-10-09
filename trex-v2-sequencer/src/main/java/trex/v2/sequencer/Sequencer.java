package trex.v2.sequencer;

import trex.v2.core.Action;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
import trex.v2.core.Envelope;
import trex.v2.core.Fact;
import trex.v2.core.IngestEvent;
import trex.v2.core.Ids;
import trex.v2.core.LogLine;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.core.Unknown;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.derive.AmountKind;
import trex.v2.core.derive.Cadence;
import trex.v2.core.derive.CommitmentKind;
import trex.v2.core.derive.ReviewItem;
import trex.v2.log.Journal;
import trex.v2.log.LogCodec;
import trex.v2.sequencer.api.BatchResponse;
import trex.v2.sequencer.api.DecisionBatch;
import trex.v2.sequencer.api.DecisionDraft;
import trex.v2.sequencer.api.FactBatch;
import trex.v2.sequencer.api.FactDraft;
import trex.v2.sequencer.api.HeadResponse;
import trex.v2.sequencer.api.RowResult;
import trex.v2.sequencer.api.StreamResponse;
import trex.v2.sequencer.SequencerState.ObsKey;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The only writer (V2-PROPOSAL.md §6.5, §6.7). It validates fields and references, assigns
 * {@code occ} and {@code externalId}, dedups whole observations, and appends facts and decisions
 * one batch at a time with one fsync. It interprets nothing: no matching, no state, no category,
 * no duplicate flag.
 *
 * <p>Semantics that belong to rules — equal-and-opposite legs, legal transitions, a plausible
 * settlement — are deliberately not enforced here (§6.8): a semantically wrong but well-formed
 * decision is recorded and surfaced as {@code INEFFECTIVE_DECISION} by derivation.
 */
public final class Sequencer implements AutoCloseable {

    private static final Set<String> REVIEW_KINDS = Set.of(
        ReviewItem.POTENTIAL_DUP, ReviewItem.RESTATEMENT, ReviewItem.AMBIGUOUS_TRANSFER,
        ReviewItem.AMBIGUOUS_SETTLEMENT, ReviewItem.UNMATCHED_LEG, ReviewItem.STALE_PENDING,
        ReviewItem.INEFFECTIVE_DECISION, ReviewItem.BALANCE_BREAK,
        ReviewItem.SUSPECTED_RECURRING, ReviewItem.DORMANT_COMMITMENT, ReviewItem.COMMITMENT_ARREARS);

    private final Journal journal;
    private final Registry registry;
    private final RuleSet categories;
    private final Clock clock;
    private final SequencerState state;
    private final String env;
    private final Set<String> allowedSources;   // null = allow any well-formed source

    private static final String NONE_TARGET = "        ";

    public Sequencer(Journal journal, Registry registry, RuleSet categories, Clock clock) {
        this(journal, registry, categories, clock, "Dev1    ", null);
    }

    public Sequencer(Journal journal, Registry registry, RuleSet categories, Clock clock,
                     String env, Set<String> allowedSources) {
        this.journal = journal;
        this.registry = registry;
        this.categories = categories;
        this.clock = clock;
        this.env = env;
        this.allowedSources = allowedSources;
        this.state = SequencerState.fold(journal.replayFrom(0));
        this.state.headOffset = journal.headOffset();
    }

    /** The writing process instance (§6): exactly 8 chars of {@code [A-Za-z0-9_]} and registered. */
    private String source(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("source is required on a batch (§6)");
        }
        if (raw.length() != 8) {
            throw new IllegalArgumentException("source must be exactly 8 characters: '" + raw + "'");
        }
        for (char c : raw.toCharArray()) {
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                throw new IllegalArgumentException("source has an illegal character: '" + c + "'");
            }
        }
        if (allowedSources != null && !allowedSources.contains(raw)) {
            throw new IllegalArgumentException("unknown source '" + raw + "'; register it in sources.yaml");
        }
        return raw;
    }

    private static String target(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE_TARGET;
        }
        if (raw.length() != 8) {
            throw new IllegalArgumentException("target must be exactly 8 characters: '" + raw + "'");
        }
        return raw;
    }

    public synchronized HeadResponse head() {
        return new HeadResponse(state.headN, journal.headOffset());
    }

    /** Append one ingest event (V2-PROPOSAL.md §12.6): the writer stamps the envelope and assigns n. */
    public synchronized HeadResponse submitIngest(trex.v2.sequencer.api.IngestBatch batch) {
        String source = source(batch.source());
        String target = target(batch.target());
        if (batch.batch() == null || batch.batch().isBlank()) {
            throw new IllegalArgumentException("batch is required on an ingest event");
        }
        IngestEvent event = new IngestEvent(
            new Envelope(state.headN + 1, IngestEvent.KIND, Envelope.VERSION, clock.millis(), env, source, target),
            batch.phase(), batch.batch(), batch.evidence(), batch.file(), batch.account(),
            batch.sourceType(), batch.parser(), batch.appended(), batch.duplicate(), batch.flagged(),
            batch.status());
        journal.appendBatch(List.of(event));
        state.headN++;
        return head();
    }

    /** The number of log lines folded; for tests and diagnostics. */
    public synchronized long headN() {
        return state.headN;
    }

    // ---- stream (§14.1) ---------------------------------------------------------------------

    /**
     * Append a validated stream: raw JSONL lines land verbatim, in {@code n} order, starting at the
     * current head + 1. Each line is re-validated as it lands — a known kind, a well-formed
     * envelope, a known {@code accountRef}, and decision cross-references resolvable in the prefix.
     * A bad line stops the ingest at that line; the prefix already landed and is reported.
     */
    public synchronized StreamResponse submitStream(List<String> rawLines) {
        long expected = state.headN + 1;
        Set<String> knownFacts = new HashSet<>(state.latestById.keySet());
        Set<Long> knownDecisions = new HashSet<>(state.decisionNs);
        Set<String> knownCommitments = new HashSet<>(state.declaredCommitments);
        List<LogLine> toAppend = new ArrayList<>();
        Long stoppedAt = null;
        String error = null;
        for (int i = 0; i < rawLines.size(); i++) {
            long n = expected + i;
            LogLine line;
            try {
                line = LogCodec.parse(rawLines.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                error = "line n=" + n + ": unparseable (" + e.getMessage() + ")";
                stoppedAt = n;
                break;
            }
            String problem = validateStreamLine(line, n, knownFacts, knownDecisions, knownCommitments);
            if (problem != null) {
                error = problem;
                stoppedAt = n;
                break;
            }
            toAppend.add(line);
            if (line instanceof Fact f) {
                knownFacts.add(f.externalId());
            } else if (line instanceof Decision d) {
                knownDecisions.add(d.n());
                if (d instanceof Decision.DeclareCommitment dc) {
                    knownCommitments.add(dc.commitmentId());
                }
            }
        }
        if (!toAppend.isEmpty()) {
            journal.appendBatch(new ArrayList<>(toAppend));
            for (LogLine line : toAppend) {
                if (line instanceof Fact f) {
                    state.observe(f);
                } else if (line instanceof Decision d) {
                    state.observe(d);
                }
            }
            state.headN = expected - 1 + toAppend.size();
        }
        return new StreamResponse(toAppend.size(), state.headN, stoppedAt, error);
    }

    private String validateStreamLine(LogLine line, long n, Set<String> knownFacts,
                                      Set<Long> knownDecisions, Set<String> knownCommitments) {
        if (line instanceof Unknown) {
            return "line n=" + n + ": unknown kind '" + line.kind() + "'";
        }
        if (line.n() != n) {
            return "line n=" + line.n() + " breaks contiguity; expected n=" + n;
        }
        try {
            source(line.envelope().source());
        } catch (IllegalArgumentException e) {
            return "line n=" + n + ": " + e.getMessage();
        }
        if (line instanceof Fact f) {
            if (registry.findAccount(f.accountRef()).isEmpty()) {
                return "line n=" + n + ": unknown accountRef '" + f.accountRef() + "'";
            }
            if (f.externalId() == null || f.externalId().isBlank()) {
                return "line n=" + n + ": externalId is required";
            }
        } else if (line instanceof Decision d) {
            return decisionRefs(d, n, knownFacts, knownDecisions, knownCommitments);
        }
        return null;
    }

    private String decisionRefs(Decision d, long n, Set<String> knownFacts, Set<Long> knownDecisions,
                                Set<String> knownCommitments) {
        List<String> refs = new ArrayList<>();
        if (d instanceof Decision.Pair p) {
            refs.add(p.legA());
            refs.add(p.legB());
        } else if (d instanceof Decision.Unpair u) {
            refs.add(u.legA());
            refs.add(u.legB());
        } else if (d instanceof Decision.MarkExternal m) {
            refs.add(m.externalId());
        } else if (d instanceof Decision.Settle s) {
            refs.add(s.pendingId());
            refs.add(s.postedId());
        } else if (d instanceof Decision.Dismiss dis) {
            // A subject is a fact id for most kinds, an account ref for BALANCE_BREAK, a grouping
            // stem for SUSPECTED_RECURRING and a declared commitment id for the commitment kinds
            // (§9.9.F, as amended). The candidate set is derived, so a stem only has to be
            // non-blank; a wrong one is ineffective in derivation.
            if (ReviewItem.BALANCE_BREAK.equals(dis.item())) {
                for (String id : dis.externalIds()) {
                    if (id == null || id.isBlank() || registry.findAccount(id).isEmpty()) {
                        return "line n=" + n + ": DISMISS names unknown account '" + id + "'";
                    }
                }
                return null;
            }
            if (ReviewItem.SUSPECTED_RECURRING.equals(dis.item())) {
                for (String id : dis.externalIds()) {
                    if (id == null || id.isBlank()) {
                        return "line n=" + n + ": DISMISS names a blank candidate";
                    }
                }
                return null;
            }
            if (ReviewItem.DORMANT_COMMITMENT.equals(dis.item())
                || ReviewItem.COMMITMENT_ARREARS.equals(dis.item())) {
                for (String id : dis.externalIds()) {
                    if (!knownCommitments.contains(id)) {
                        return "line n=" + n + ": names an undeclared commitment '" + id + "'";
                    }
                }
                return null;
            }
            refs.addAll(dis.externalIds());
        } else if (d instanceof Decision.Pin pin) {
            refs.addAll(pin.externalIds());
        } else if (d instanceof Decision.Unpin up) {
            refs.addAll(up.externalIds());
        } else if (d instanceof Decision.Supersede s) {
            refs.add(s.fromId());
            refs.add(s.toId());
        } else if (d instanceof Decision.Retire r) {
            refs.add(r.externalId());
        } else if (d instanceof Decision.MarkNoop m) {
            refs.add(m.externalId());
        } else if (d instanceof Decision.UnmarkNoop u) {
            refs.add(u.externalId());
        } else if (d instanceof Decision.UserAck a) {
            refs.add(a.externalId());
        } else if (d instanceof Decision.UserUnack u) {
            refs.add(u.externalId());
        } else if (d instanceof Decision.Note note) {
            refs.add(note.externalId());
        } else if (d instanceof Decision.PinCommitment pc) {
            refs.addAll(pc.externalIds());
            if (!knownCommitments.contains(pc.commitmentId())) {
                return "line n=" + n + ": names an undeclared commitment '" + pc.commitmentId() + "'";
            }
        } else if (d instanceof Decision.NoteCommitment nc) {
            if (!knownCommitments.contains(nc.commitmentId())) {
                return "line n=" + n + ": names an undeclared commitment '" + nc.commitmentId() + "'";
            }
        } else if (d instanceof Decision.SettleOccurrence so) {
            if (!knownCommitments.contains(so.commitmentId())) {
                return "line n=" + n + ": names an undeclared commitment '" + so.commitmentId() + "'";
            }
        } else if (d instanceof Decision.Revoke rv) {
            if (!knownDecisions.contains(rv.target())) {
                return "line n=" + n + ": REVOKE names unknown decision n=" + rv.target();
            }
            return null;
        }
        for (String ref : refs) {
            if (ref != null && !ref.isBlank() && !knownFacts.contains(ref)) {
                return "line n=" + n + ": names an unknown fact '" + ref + "'";
            }
        }
        return null;
    }

    // ---- facts ------------------------------------------------------------------------------

    public synchronized BatchResponse submitFacts(FactBatch batch) {
        String source = source(batch.source());
        String target = target(batch.target());
        List<FactDraft> drafts = batch.facts() == null ? List.of() : batch.facts();
        List<RowResult> results = new ArrayList<>();
        String[] errors = new String[drafts.size()];
        boolean anyRejected = false;
        for (int i = 0; i < drafts.size(); i++) {
            errors[i] = validateDraft(drafts.get(i));
            anyRejected |= errors[i] != null;
        }

        if (batch.allOrNone() && anyRejected) {
            for (int i = 0; i < drafts.size(); i++) {
                results.add(errors[i] != null
                    ? rejected(ref(i, drafts.get(i)), errors[i])
                    : new RowResult(ref(i, drafts.get(i)), RowResult.REJECTED, null, null,
                        "batch rejected (allOrNone)"));
            }
            return new BatchResponse(handle(), BatchResponse.REJECTED, results);
        }

        // Mint over the rows that passed validation, in batch order: a rejected row claims no occ.
        List<Ids.Row> mintRows = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            if (errors[i] == null) {
                FactDraft d = drafts.get(i);
                mintRows.add(new Ids.Row(d.accountRef(), d.date(), d.amount(), d.rawDescription(),
                    normalized(d.receipt())));
            }
        }
        java.util.Iterator<Ids.Minted> minted = Ids.mint(mintRows).iterator();
        Set<ObsKey> batchSeen = new HashSet<>();
        List<Fact> toAppend = new ArrayList<>();
        long next = state.headN;
        boolean wroteAny = false;

        for (int i = 0; i < drafts.size(); i++) {
            FactDraft d = drafts.get(i);
            if (errors[i] != null) {
                results.add(rejected(ref(i, d), errors[i]));
                continue;
            }
            Ids.Minted m = minted.next();
            int occ = m.occ();
            String receipt = normalized(d.receipt());
            String id = m.id();
            ObsKey key = new ObsKey(id, d.accountRef(), d.date(), d.amount(), d.rawDescription(), receipt, occ,
                d.balance());
            if (state.observations.contains(key) || !batchSeen.add(key)) {
                results.add(new RowResult(ref(i, d), RowResult.DUPLICATE, id, null, null));
                continue;
            }
            boolean known = state.latestById.containsKey(id);
            long atMs = d.ingestedAt() == null ? clock.millis() : d.ingestedAt().toEpochMilli();
            Fact fact = new Fact(
                new Envelope(next + 1, Fact.KIND, Envelope.VERSION, atMs, env, source, target),
                id, d.accountRef(), d.date(), d.amount(), d.balance(),
                d.rawDescription(), receipt, occ,
                d.observation() == null ? Observation.POSTED : d.observation(),
                d.sourceType(),
                d.provenance() == null ? Provenance.BANK : d.provenance(),
                d.evidenceId(), d.parser());
            toAppend.add(fact);
            next++;
            wroteAny = true;
            results.add(new RowResult(ref(i, d), known ? RowResult.FLAGGED : RowResult.APPENDED, id, fact.n(), null));
        }

        if (wroteAny) {
            journal.appendBatch(new ArrayList<>(toAppend));
            toAppend.forEach(state::observe);
            state.headN = next;
        }
        String status = anyRejected
            ? (wroteAny ? BatchResponse.PARTIAL : BatchResponse.REJECTED)
            : BatchResponse.COMMITTED;
        return new BatchResponse(handle(), status, results);
    }

    private String validateDraft(FactDraft d) {
        if (d == null) {
            return "missing fact";
        }
        if (d.accountRef() == null || d.accountRef().isBlank()) {
            return "accountRef is required";
        }
        if (registry.findAccount(d.accountRef()).isEmpty()) {
            return "unknown accountRef '" + d.accountRef() + "'";
        }
        if (d.date() == null) {
            return "date is required";
        }
        if (d.amount() == null) {
            return "amount is required";
        }
        if (d.balance() == null) {
            return "balance is required";
        }
        if (d.rawDescription() == null || d.rawDescription().isBlank()) {
            return "rawDescription is required";
        }
        if (d.sourceType() == null || d.sourceType().isBlank()) {
            return "sourceType is required";
        }
        return null;
    }

    // ---- decisions --------------------------------------------------------------------------

    public synchronized BatchResponse submitDecisions(DecisionBatch batch) {
        String source = source(batch.source());
        String target = target(batch.target());
        List<DecisionDraft> drafts = batch.decisions() == null ? List.of() : batch.decisions();
        boolean allOrNone = Boolean.TRUE.equals(batch.allOrNone());
        List<RowResult> results = new ArrayList<>();
        List<Decision> toAppend = new ArrayList<>();
        Set<String> declaredCommitments = new HashSet<>(state.declaredCommitments);
        String[] errors = new String[drafts.size()];
        long next = state.headN;
        boolean anyRejected = false;
        for (int i = 0; i < drafts.size(); i++) {
            DecisionDraft d = drafts.get(i);
            try {
                Decision decision = buildDecision(next + 1, d, source, target, declaredCommitments);
                toAppend.add(decision);
                if (decision instanceof Decision.DeclareCommitment dc) {
                    declaredCommitments.add(dc.commitmentId());
                }
                next++;
                results.add(new RowResult(ref(i, d), RowResult.RESOLVED, null, decision.n(), null));
            } catch (IllegalArgumentException e) {
                errors[i] = e.getMessage();
                anyRejected = true;
                results.add(new RowResult(ref(i, d), RowResult.REJECTED, null, null, e.getMessage()));
            }
        }
        if (allOrNone && anyRejected) {
            results.clear();
            for (int i = 0; i < drafts.size(); i++) {
                results.add(errors[i] != null
                    ? rejected(ref(i, drafts.get(i)), errors[i])
                    : new RowResult(ref(i, drafts.get(i)), RowResult.REJECTED, null, null,
                        "batch rejected (allOrNone)"));
            }
            return new BatchResponse(handle(), BatchResponse.REJECTED, results);
        }
        if (!toAppend.isEmpty()) {
            journal.appendBatch(new ArrayList<>(toAppend));
            toAppend.forEach(state::observe);
            state.headN = next;
        }
        String status = anyRejected
            ? (toAppend.isEmpty() ? BatchResponse.REJECTED : BatchResponse.PARTIAL)
            : BatchResponse.COMMITTED;
        return new BatchResponse(handle(), status, results);
    }

    private Decision buildDecision(long n, DecisionDraft d, String source, String target,
                                   Set<String> declaredCommitments) {
        if (d == null) {
            throw new IllegalArgumentException("missing decision");
        }
        if (d.action() == null || d.action().isBlank()) {
            throw new IllegalArgumentException("action is required");
        }
        Action action = Action.fromWire(d.action());
        Actor actor = require(d.actor() == null ? null : Actor.fromWire(d.actor()), "actor");
        String user = d.user();
        if (actor == Actor.USER) {
            if (user == null || user.isBlank()) {
                throw new IllegalArgumentException("a user decision must name its user");
            }
            if (registry.findUser(user).isEmpty()) {
                throw new IllegalArgumentException("unknown user '" + user + "'");
            }
        }
        if (actor != Actor.USER) {
            user = null;
        }
        Instant at = d.at() == null ? clock.instant() : d.at();
        Envelope envelope = new Envelope(n, Decision.KIND, Envelope.VERSION, at.toEpochMilli(),
            env, source, target);

        return switch (action) {
            case PAIR -> new Decision.Pair(envelope, requireDistinct(d.legA(), d.legB(), "legA", "legB"),
                requireFact(d.legB(), "legB"), d.comment(), actor, user);
            case UNPAIR -> new Decision.Unpair(envelope, requireDistinct(d.legA(), d.legB(), "legA", "legB"),
                requireFact(d.legB(), "legB"), d.comment(), actor, user);
            case MARK_EXTERNAL -> new Decision.MarkExternal(envelope, requireFact(d.externalId(), "externalId"),
                d.comment(), actor, user);
            case SETTLE -> new Decision.Settle(envelope, requireFact(d.pendingId(), "pendingId"),
                requireFact(d.postedId(), "postedId"), d.comment(), actor, user);
            case DISMISS -> {
                String item = requireItem(d.item());
                yield new Decision.Dismiss(envelope, item,
                    dismissSubjects(item, d.externalIds(), declaredCommitments), d.comment(), actor, user);
            }
            case PIN -> new Decision.Pin(envelope, requireFacts(d.externalIds(), "externalIds"),
                requireCategory(d.category()), d.comment(), actor, user);
            case UNPIN -> new Decision.Unpin(envelope, requireFacts(d.externalIds(), "externalIds"),
                d.comment(), actor, user);
            case SUPERSEDE -> {
                String from = requireFact(d.fromId(), "fromId");
                String to = requireFact(d.toId(), "toId");
                if (from.equals(to)) {
                    throw new IllegalArgumentException("SUPERSEDE cannot replace a fact with itself");
                }
                yield new Decision.Supersede(envelope, from, to, require(d.reason(), "reason"), actor, user);
            }
            case RETIRE -> new Decision.Retire(envelope, requireFact(d.externalId(), "externalId"),
                require(d.reason(), "reason"), actor, user);
            case MARK_NOOP -> new Decision.MarkNoop(envelope, requireFact(d.externalId(), "externalId"),
                require(d.reason(), "reason"), actor, user);
            case UNMARK_NOOP -> new Decision.UnmarkNoop(envelope, requireFact(d.externalId(), "externalId"),
                d.comment(), actor, user);
            case REVOKE -> {
                if (d.target() == null || !state.decisionNs.contains(d.target())) {
                    throw new IllegalArgumentException("REVOKE names a decision n that does not exist: " + d.target());
                }
                yield new Decision.Revoke(envelope, d.target(), d.comment(), actor, user);
            }
            case USER_ACK -> new Decision.UserAck(envelope, requireFact(d.externalId(), "externalId"),
                require(d.configRevision(), "configRevision"), require(d.deriveVersion(), "deriveVersion"),
                require(d.hashVersion(), "hashVersion"), require(d.stateHash(), "stateHash"),
                d.comment(), actor, user);
            case USER_UNACK -> new Decision.UserUnack(envelope, requireFact(d.externalId(), "externalId"),
                d.comment(), actor, user);
            case NOTE -> new Decision.Note(envelope, d.externalId(), require(d.text(), "text"), actor, user);
            case ATTACH_ACCOUNT -> new Decision.AttachAccount(envelope,
                requireFacts(d.externalIds(), "externalIds"), requireClearingAccount(d.account()),
                d.comment(), actor, user);
            case DECLARE_COMMITMENT -> new Decision.DeclareCommitment(envelope,
                require(d.commitmentId(), "commitmentId"), require(d.name(), "name"),
                requireDirection(d.direction()), requireCadence(d.cadence()),
                requireAmountKind(d.amountKind()), requireCommitmentKind(d.kind()),  // wire field "kind"
                requireMatches(d.matches()), d.amount(), d.anchor(), d.fromCandidate(), d.comment(),
                actor, user);
            case RETIRE_COMMITMENT -> new Decision.RetireCommitment(envelope,
                require(d.commitmentId(), "commitmentId"), require(d.endedAt(), "endedAt"),
                require(d.reason(), "reason"), actor, user);
            case IGNORE_RECURRING -> new Decision.IgnoreRecurring(envelope,
                require(d.candidate(), "candidate"), require(d.reason(), "reason"), actor, user);
            case PIN_COMMITMENT -> new Decision.PinCommitment(envelope,
                requireDeclaredCommitment(d.commitmentId(), declaredCommitments),
                requireFacts(d.externalIds(), "externalIds"), d.comment(), actor, user);
            case UNPIN_COMMITMENT -> new Decision.UnpinCommitment(envelope,
                requireFacts(d.externalIds(), "externalIds"), d.comment(), actor, user);
            case EXCLUDE_COMMITMENT -> new Decision.ExcludeCommitment(envelope,
                requireDeclaredCommitment(d.commitmentId(), declaredCommitments),
                requireFacts(d.externalIds(), "externalIds"), d.comment(), actor, user);
            case INCLUDE_COMMITMENT -> new Decision.IncludeCommitment(envelope,
                requireDeclaredCommitment(d.commitmentId(), declaredCommitments),
                requireFacts(d.externalIds(), "externalIds"), d.comment(), actor, user);
            case NOTE_COMMITMENT -> new Decision.NoteCommitment(envelope,
                requireDeclaredCommitment(d.commitmentId(), declaredCommitments),
                require(d.text(), "text"), actor, user);
            case SETTLE_OCCURRENCE -> new Decision.SettleOccurrence(envelope,
                requireDeclaredCommitment(d.commitmentId(), declaredCommitments),
                requireDates(d.dueDates(), "dueDates"), d.comment(), actor, user);
        };
    }

    private String requireClearingAccount(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("account is required");
        }
        var account = registry.findAccount(ref);
        if (account.isEmpty()) {
            throw new IllegalArgumentException("unknown account '" + ref + "'");
        }
        if (!account.get().clearing()) {
            throw new IllegalArgumentException("ATTACH_ACCOUNT must name a clearing account, not '" + ref + "'");
        }
        return ref;
    }

    private static Actor require(Actor actor, String name) {
        if (actor == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return actor;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static long require(Long value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static LocalDate require(LocalDate value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String requireDirection(String direction) {
        require(direction, "direction");
        if (!"in".equals(direction) && !"out".equals(direction)) {
            throw new IllegalArgumentException("direction must be 'in' or 'out', not '" + direction + "'");
        }
        return direction;
    }

    private static Cadence requireCadence(String wire) {
        require(wire, "cadence");
        return Cadence.fromWire(wire);
    }

    private static AmountKind requireAmountKind(String wire) {
        require(wire, "amountKind");
        return AmountKind.fromWire(wire);
    }

    private static CommitmentKind requireCommitmentKind(String wire) {
        require(wire, "kind");
        return CommitmentKind.fromWire(wire);
    }

    /** The declaration's rules: a non-empty list of non-blank regexes; blank accounts normalise to null. */
    private static List<Decision.Match> requireMatches(List<DecisionDraft.MatchDraft> matches) {
        if (matches == null || matches.isEmpty()) {
            throw new IllegalArgumentException("matches is required");
        }
        List<Decision.Match> out = new ArrayList<>();
        for (DecisionDraft.MatchDraft m : matches) {
            if (m == null) {
                throw new IllegalArgumentException("each match must name a regex");
            }
            out.add(new Decision.Match(require(m.match(), "match"),
                m.account() == null || m.account().isBlank() ? null : m.account()));
        }
        return List.copyOf(out);
    }

    private static List<LocalDate> requireDates(List<LocalDate> dates, String name) {
        if (dates == null || dates.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        for (LocalDate date : dates) {
            if (date == null) {
                throw new IllegalArgumentException(name + " must not contain a null date");
            }
        }
        return List.copyOf(dates);
    }

    private static String requireDeclaredCommitment(String id, Set<String> declared) {
        require(id, "commitmentId");
        if (!declared.contains(id)) {
            throw new IllegalArgumentException("commitmentId names an undeclared commitment '" + id + "'");
        }
        return id;
    }

    private String requireFact(String id, String name) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (!state.latestById.containsKey(id)) {
            throw new IllegalArgumentException(name + " names an unknown fact '" + id + "'");
        }
        return id;
    }

    /** A pair names two distinct facts; A paired with itself is a structural fault, not a rule. */
    private String requireDistinct(String a, String b, String nameA, String nameB) {
        requireFact(a, nameA);
        if (a.equals(b)) {
            throw new IllegalArgumentException(nameA + " and " + nameB + " must be different facts");
        }
        return a;
    }

    private List<String> requireFacts(List<String> ids, String name) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        for (String id : ids) {
            requireFact(id, name);
        }
        return List.copyOf(ids);
    }

    private String requireCategory(String category) {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category is required");
        }
        if (RuleSet.isReserved(category)) {
            throw new IllegalArgumentException("'" + category + "' is reserved and cannot be pinned");
        }
        if (!categories.isDeclared(category)) {
            throw new IllegalArgumentException("category '" + category + "' is not declared");
        }
        return category;
    }

    private static String requireItem(String item) {
        if (item == null || !REVIEW_KINDS.contains(item)) {
            throw new IllegalArgumentException("item must be a review kind, not '" + item + "'");
        }
        return item;
    }

    /**
     * A DISMISS's subjects per review kind (V2-PROPOSAL.md §9.9.F, as amended): a fact id for most
     * kinds, an account ref for {@code BALANCE_BREAK}, the grouping stem for
     * {@code SUSPECTED_RECURRING} and a declared commitment id for {@code DORMANT_COMMITMENT}/
     * {@code COMMITMENT_ARREARS} (a retired id still counts — its history remains dismissable). The
     * candidate set is derived, so the writer cannot know a stem; a wrong one is recorded and
     * surfaces as {@code INEFFECTIVE_DECISION} in derivation (§2.6).
     */
    private List<String> dismissSubjects(String item, List<String> ids, Set<String> declaredCommitments) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("externalIds is required");
        }
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("externalIds must not contain a blank subject");
            }
        }
        if (ReviewItem.BALANCE_BREAK.equals(item)) {
            for (String id : ids) {
                if (registry.findAccount(id).isEmpty()) {
                    throw new IllegalArgumentException("externalIds names an unknown account '" + id + "'");
                }
            }
            return List.copyOf(ids);
        }
        if (ReviewItem.SUSPECTED_RECURRING.equals(item)) {
            return List.copyOf(ids);
        }
        if (ReviewItem.DORMANT_COMMITMENT.equals(item) || ReviewItem.COMMITMENT_ARREARS.equals(item)) {
            for (String id : ids) {
                if (!declaredCommitments.contains(id)) {
                    throw new IllegalArgumentException("DISMISS names an undeclared commitment '" + id + "'");
                }
            }
            return List.copyOf(ids);
        }
        return requireFacts(ids, "externalIds");
    }

    private static String normalized(String receipt) {
        return receipt == null || receipt.isBlank() ? null : receipt;
    }

    private static RowResult rejected(String ref, String reason) {
        return new RowResult(ref, RowResult.REJECTED, null, null, reason);
    }

    private static String ref(int index, FactDraft draft) {
        return "fact[" + index + "]";
    }

    private static String ref(int index, DecisionDraft draft) {
        return "decision[" + index + "]";
    }

    private static String handle() {
        return UUID.randomUUID().toString();
    }

    @Override
    public void close() {
        journal.close();
    }
}
