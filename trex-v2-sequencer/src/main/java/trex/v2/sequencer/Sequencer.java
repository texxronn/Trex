package trex.v2.sequencer;

import trex.v2.core.Action;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.Ids;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.derive.ReviewItem;
import trex.v2.log.Journal;
import trex.v2.sequencer.api.BatchResponse;
import trex.v2.sequencer.api.DecisionBatch;
import trex.v2.sequencer.api.DecisionDraft;
import trex.v2.sequencer.api.FactBatch;
import trex.v2.sequencer.api.FactDraft;
import trex.v2.sequencer.api.HeadResponse;
import trex.v2.sequencer.api.RowResult;
import trex.v2.sequencer.SequencerState.ObsKey;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
        ReviewItem.INEFFECTIVE_DECISION);

    private final Journal journal;
    private final Registry registry;
    private final RuleSet categories;
    private final Clock clock;
    private final SequencerState state;

    public Sequencer(Journal journal, Registry registry, RuleSet categories, Clock clock) {
        this.journal = journal;
        this.registry = registry;
        this.categories = categories;
        this.clock = clock;
        this.state = SequencerState.fold(journal.replayFrom(0));
        this.state.headOffset = journal.headOffset();
    }

    public synchronized HeadResponse head() {
        return new HeadResponse(state.headN, journal.headOffset());
    }

    /** The number of log lines folded; for tests and diagnostics. */
    public synchronized long headN() {
        return state.headN;
    }

    // ---- facts ------------------------------------------------------------------------------

    public synchronized BatchResponse submitFacts(FactBatch batch) {
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

        Map<String, Integer> contentCounts = new HashMap<>();
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
            int occ = assignOcc(d, contentCounts);
            String receipt = normalized(d.receipt());
            String id = Ids.externalId(d.accountRef(), d.date(), d.amount(), d.rawDescription(), receipt, occ);
            ObsKey key = new ObsKey(id, d.accountRef(), d.date(), d.amount(), d.rawDescription(), receipt, occ,
                d.balance());
            if (state.observations.contains(key) || !batchSeen.add(key)) {
                results.add(new RowResult(ref(i, d), RowResult.DUPLICATE, id, null, null));
                continue;
            }
            boolean known = state.latestById.containsKey(id);
            Fact fact = new Fact(next + 1, id, d.accountRef(), d.date(), d.amount(), d.balance(),
                d.rawDescription(), receipt, occ,
                d.observation() == null ? Observation.POSTED : d.observation(),
                d.sourceType(),
                d.provenance() == null ? Provenance.BANK : d.provenance(),
                d.evidenceId(), d.parser(),
                d.ingestedAt() == null ? clock.instant() : d.ingestedAt());
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

    /**
     * The occurrence index (V2-PROPOSAL.md §6.1, v1's rule): a receipt-keyed row is identified by its
     * natural key and carries {@code occ 0}; a content-hash row takes the count of earlier rows in
     * this batch with the same {@code (account, day, amount, rawDescription)}. Distinct rows on a
     * day are each {@code occ 0}; identical rows get {@code 0, 1, 2}. This is what keeps identity
     * stable across a v1 import and a re-parse.
     */
    private static int assignOcc(FactDraft d, Map<String, Integer> contentCounts) {
        if (d.receipt() != null && !d.receipt().isBlank()) {
            return 0;
        }
        String key = d.accountRef() + '\u0000' + d.date() + '\u0000' + d.amount() + '\u0000' + d.rawDescription();
        int occ = contentCounts.getOrDefault(key, 0);
        contentCounts.put(key, occ + 1);
        return occ;
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
        List<DecisionDraft> drafts = batch.decisions() == null ? List.of() : batch.decisions();
        boolean allOrNone = Boolean.TRUE.equals(batch.allOrNone());
        List<RowResult> results = new ArrayList<>();
        List<Decision> toAppend = new ArrayList<>();
        String[] errors = new String[drafts.size()];
        long next = state.headN;
        boolean anyRejected = false;
        for (int i = 0; i < drafts.size(); i++) {
            DecisionDraft d = drafts.get(i);
            try {
                Decision decision = buildDecision(next + 1, d);
                toAppend.add(decision);
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
            toAppend.forEach(dec -> state.decisionNs.add(dec.n()));
            state.headN = next;
        }
        String status = anyRejected
            ? (toAppend.isEmpty() ? BatchResponse.REJECTED : BatchResponse.PARTIAL)
            : BatchResponse.COMMITTED;
        return new BatchResponse(handle(), status, results);
    }

    private Decision buildDecision(long n, DecisionDraft d) {
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

        return switch (action) {
            case PAIR -> new Decision.Pair(n, requireDistinct(d.legA(), d.legB(), "legA", "legB"),
                requireFact(d.legB(), "legB"), d.comment(), actor, user, at);
            case UNPAIR -> new Decision.Unpair(n, requireDistinct(d.legA(), d.legB(), "legA", "legB"),
                requireFact(d.legB(), "legB"), d.comment(), actor, user, at);
            case MARK_EXTERNAL -> new Decision.MarkExternal(n, requireFact(d.externalId(), "externalId"),
                d.comment(), actor, user, at);
            case SETTLE -> new Decision.Settle(n, requireFact(d.pendingId(), "pendingId"),
                requireFact(d.postedId(), "postedId"), d.comment(), actor, user, at);
            case DISMISS -> new Decision.Dismiss(n, requireItem(d.item()), requireFacts(d.externalIds(), "externalIds"),
                d.comment(), actor, user, at);
            case PIN -> new Decision.Pin(n, requireFacts(d.externalIds(), "externalIds"),
                requireCategory(d.category()), d.comment(), actor, user, at);
            case UNPIN -> new Decision.Unpin(n, requireFacts(d.externalIds(), "externalIds"),
                d.comment(), actor, user, at);
            case SUPERSEDE -> {
                String from = requireFact(d.fromId(), "fromId");
                String to = requireFact(d.toId(), "toId");
                if (from.equals(to)) {
                    throw new IllegalArgumentException("SUPERSEDE cannot replace a fact with itself");
                }
                yield new Decision.Supersede(n, from, to, require(d.reason(), "reason"), actor, user, at);
            }
            case RETIRE -> new Decision.Retire(n, requireFact(d.externalId(), "externalId"),
                require(d.reason(), "reason"), actor, user, at);
            case REVOKE -> {
                if (d.target() == null || !state.decisionNs.contains(d.target())) {
                    throw new IllegalArgumentException("REVOKE names a decision n that does not exist: " + d.target());
                }
                yield new Decision.Revoke(n, d.target(), d.comment(), actor, user, at);
            }
            case USER_ACK -> new Decision.UserAck(n, requireFact(d.externalId(), "externalId"),
                require(d.configRevision(), "configRevision"), require(d.deriveVersion(), "deriveVersion"),
                require(d.hashVersion(), "hashVersion"), require(d.stateHash(), "stateHash"),
                d.comment(), actor, user, at);
            case USER_UNACK -> new Decision.UserUnack(n, requireFact(d.externalId(), "externalId"),
                d.comment(), actor, user, at);
            case NOTE -> new Decision.Note(n, d.externalId(), require(d.text(), "text"), actor, user, at);
        };
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
