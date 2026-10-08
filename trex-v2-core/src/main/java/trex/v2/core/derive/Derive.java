package trex.v2.core.derive;

import trex.v2.core.Action;
import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.Hashes;
import trex.v2.core.Ids;
import trex.v2.core.MerchantStem;
import trex.v2.core.Observation;
import trex.v2.core.Rail;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Rule;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.RuleSubject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The pure derivation (V2-PROPOSAL.md §9.1, §9.9): {@code derive(facts, decisions, config, asOf)}
 * returns the §7.2 materialisation. No I/O, no ambient clock, no environment, no unordered
 * iteration; the same inputs always give the same output.
 *
 * <p>Stages run in the §9.9 order and each reads only earlier stages: replay is the caller's
 * (it hands us the folded facts and decisions), then effective decisions, supersession, current
 * facts, pairing, pending (P4 — empty for now), category, commitments (a sibling of category,
 * V2-COMMITMENTS-PLAN.md §2), review items and units.
 */
public final class Derive {

    private static final Comparator<Fact> BY_DATE_N = Comparator.comparing(Fact::date).thenComparingLong(Fact::n);

    private Derive() {}

    public static Derivation derive(List<Fact> facts, List<Decision> decisions, DeriveConfig config, Instant asOf) {
        return new Ctx(facts, decisions, config, asOf).run();
    }

    private static final class Ctx {

        final List<Fact> facts;
        final List<Decision> decisions;
        final DeriveConfig config;
        final Instant asOf;

        final Map<String, List<Fact>> factsById = new TreeMap<>();
        final Map<String, Fact> latestById = new TreeMap<>();
        final List<IneffectiveDecision> ineffective = new ArrayList<>();
        final Map<String, Supersession> supersession = new TreeMap<>();

        List<Decision> effective;
        List<CurrentFact> current = List.of();
        Map<String, Role> roleOverrides = new HashMap<>();
        Map<String, LegState> legState = new HashMap<>();
        Map<String, String> transferIdByLeg = new HashMap<>();
        List<TransferRow> transfers = List.of();
        List<ReviewItem> pairingReview = new ArrayList<>();
        List<ReviewItem> pendingReview = new ArrayList<>();
        List<PendingRow> pending = List.of();

        // The commitment stage's output (P10): folded declarations, detected candidates, matched
        // occurrences and the review items they raise.
        List<Commitment> commitmentRows = List.of();
        List<CommitmentRule> commitmentRules = List.of();
        List<CommitmentOccurrence> commitmentOccurrences = List.of();
        List<CommitmentNote> commitmentNotes = List.of();
        List<ReviewItem> commitmentReview = List.of();
        Set<String> commitmentIds = new TreeSet<>();
        Set<String> candidateKeys = new TreeSet<>();
        Map<String, Long> newestFactByStem = new TreeMap<>();
        Map<String, Long> newestFactByCommitment = new TreeMap<>();

        Ctx(List<Fact> facts, List<Decision> decisions, DeriveConfig config, Instant asOf) {
            this.facts = facts.stream().sorted(Comparator.comparingLong(Fact::n)).toList();
            this.decisions = decisions.stream().sorted(Comparator.comparingLong(Decision::n)).toList();
            this.config = config;
            this.asOf = asOf;
            for (Fact f : this.facts) {
                factsById.computeIfAbsent(f.externalId(), k -> new ArrayList<>()).add(f);
                latestById.merge(f.externalId(), f, (a, b) -> a.n() >= b.n() ? a : b);
            }
        }

        Derivation run() {
            effective = effectiveDecisions();
            buildSupersession();
            collectRoleOverrides();

            Map<String, CurrentFact> currentMap = currentFacts();
            pairing(new ArrayList<>(currentMap.values()));
            Map<String, CurrentFact> withPairing = applyPairing(currentMap);
            Map<String, CategoryRow> categories = categories(withPairing);
            current = withPairing.values().stream()
                .map(c -> withCategory(c, categories.get(c.externalId())))
                .map(this::withStateHash)
                .sorted(Comparator.comparing((CurrentFact c) -> c.fact().date())
                    .thenComparingLong(c -> c.fact().n()))
                .toList();

            commitments();

            pending = pendingSettlement();
            List<ReviewItem> review = reviewItems();
            List<Unit> units = units();
            List<NoteRow> notes = notes();
            List<ClearingLeg> clearingLegs = clearingLegs();
            ineffective.sort(Comparator.comparingLong(IneffectiveDecision::decisionN)
                .thenComparing(IneffectiveDecision::action));

            return new Derivation(
                chainResolved(),
                new ArrayList<>(supersession.values()),
                current,
                transfers,
                pending,
                new ArrayList<>(categories.values()),
                pins(),
                notes,
                clearingLegs,
                review,
                ineffective,
                units,
                userAcks(),
                commitmentRows,
                commitmentRules,
                commitmentOccurrences,
                commitmentNotes);
        }

        /** The latest effective USER_ACK/USER_UNACK per (user, row) (V2-PROPOSAL.md §9.4). */
        private List<UserAckRow> userAcks() {
            Map<String, Decision> latest = new TreeMap<>();
            for (Decision d : effective) {
                String raw;
                if (d instanceof Decision.UserAck ack) {
                    raw = ack.externalId();
                } else if (d instanceof Decision.UserUnack unack) {
                    raw = unack.externalId();
                } else {
                    continue;
                }
                String id = resolve(raw);
                latest.put(d.user() + '\u0000' + (id == null ? raw : id), d);
            }
            List<UserAckRow> out = new ArrayList<>();
            for (Decision d : latest.values()) {
                if (d instanceof Decision.UserAck ack) {
                    String id = resolve(ack.externalId());
                    out.add(new UserAckRow(ack.user(), id == null ? ack.externalId() : id, ack.stateHash(),
                        ack.configRevision(), ack.deriveVersion(), ack.hashVersion(), ack.at(), ack.n()));
                }
            }
            return out;
        }

        // ---- P4: effective decisions (REVOKEs applied) ------------------------------------

        private List<Decision> effectiveDecisions() {
            Set<Long> decisionNs = new HashSet<>();
            for (Decision d : decisions) {
                decisionNs.add(d.n());
            }
            Map<Long, Boolean> revoked = new HashMap<>();
            List<Decision> eff = new ArrayList<>();
            for (int i = decisions.size() - 1; i >= 0; i--) {
                Decision d = decisions.get(i);
                boolean rev = revoked.getOrDefault(d.n(), false);
                if (d instanceof Decision.Revoke r) {
                    if (!decisionNs.contains(r.target())) {
                        ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                            "REVOKE names missing decision n=" + r.target()));
                        continue;
                    }
                    if (!rev) {
                        revoked.merge(r.target(), true, (a, b) -> !a);
                        eff.add(d);
                    }
                } else if (!rev) {
                    eff.add(d);
                }
            }
            Collections.reverse(eff);
            return eff;
        }

        // ---- P5: supersession ---------------------------------------------------------------

        private void buildSupersession() {
            Map<String, Supersession> cand = new TreeMap<>();
            for (Decision d : effective) {
                if (d instanceof Decision.Supersede s) {
                    if (!known(s.fromId()) || !known(s.toId())) {
                        ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                            "SUPERSEDE names an unknown id (" + s.fromId() + " -> " + s.toId() + ")"));
                    } else {
                        cand.put(s.fromId(), new Supersession(s.fromId(), s.toId(), s.n(), s.reason()));
                    }
                } else if (d instanceof Decision.Retire r) {
                    if (!known(r.externalId())) {
                        ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                            "RETIRE names an unknown id " + r.externalId()));
                    } else {
                        cand.put(r.externalId(), new Supersession(r.externalId(), null, r.n(), r.reason()));
                    }
                }
            }
            // A SUPERSEDE that points at a retired fact is ineffective (§8.3).
            Set<String> retired = new TreeSet<>();
            for (Supersession s : cand.values()) {
                if (s.retired()) {
                    retired.add(s.fromId());
                }
            }
            List<String> badTarget = new ArrayList<>();
            for (Supersession s : cand.values()) {
                if (!s.retired() && retired.contains(s.toId())) {
                    ineffective.add(new IneffectiveDecision(s.decisionN(), Action.SUPERSEDE.wire(),
                        "SUPERSEDE points at retired fact " + s.toId()));
                    badTarget.add(s.fromId());
                }
            }
            badTarget.forEach(cand::remove);

            // A cycle would make resolution ambiguous; every decision in it is ineffective (§9.9.B).
            Set<String> done = new HashSet<>();
            for (String start : new ArrayList<>(cand.keySet())) {
                if (done.contains(start)) {
                    continue;
                }
                Map<String, Integer> path = new LinkedHashMap<>();
                String cur = start;
                while (cur != null && cand.containsKey(cur) && cand.get(cur).toId() != null && !done.contains(cur)) {
                    if (path.containsKey(cur)) {
                        List<String> nodes = new ArrayList<>(path.keySet());
                        for (int i = path.get(cur); i < nodes.size(); i++) {
                            String node = nodes.get(i);
                            ineffective.add(new IneffectiveDecision(cand.get(node).decisionN(), Action.SUPERSEDE.wire(),
                                "supersession cycle at " + node));
                        }
                        path.keySet().forEach(cand::remove);
                        break;
                    }
                    path.put(cur, path.size());
                    cur = cand.get(cur).toId();
                }
                done.addAll(path.keySet());
            }

            supersession.putAll(cand);
            ineffective.sort(Comparator.comparingLong(IneffectiveDecision::decisionN)
                .thenComparing(IneffectiveDecision::action));
        }

        private boolean known(String id) {
            return factsById.containsKey(id);
        }

        /** Any id in the log to the id of its chain's current fact; retired ids map to themselves. */
        private String resolve(String id) {
            if (!known(id)) {
                return null;
            }
            String cur = id;
            Set<String> seen = new HashSet<>();
            while (supersession.containsKey(cur) && supersession.get(cur).toId() != null) {
                if (!seen.add(cur)) {
                    return null;
                }
                cur = supersession.get(cur).toId();
            }
            return cur;
        }

        private Map<String, String> chainResolved() {
            Map<String, String> out = new TreeMap<>();
            for (String id : factsById.keySet()) {
                String r = resolve(id);
                out.put(id, r == null ? id : r);
            }
            return out;
        }

        // ---- P6: current fact per chain -----------------------------------------------------

        private Map<String, CurrentFact> currentFacts() {
            Map<String, CurrentFact> out = new LinkedHashMap<>();
            for (String id : new TreeSet<>(factsById.keySet())) {
                if (supersession.containsKey(id)) {
                    continue; // superseded or retired
                }
                Fact f = latestById.get(id);
                if (f.observation() == Observation.POSTED) {
                    out.put(id, new CurrentFact(f, roleFor(f), railFor(f), LegState.EXTERNAL, null, null,
                        CategoryOrigin.NONE, null, null));
                }
            }
            return out;
        }

        /**
         * The latest effective {@code MARK_NOOP}/{@code UNMARK_NOOP} per resolved id (V2-PROPOSAL.md
         * §6.9). {@code effective} is n-ordered, so a later decision overwrites an earlier one; a
         * decision naming an unknown id is ineffective and surfaces as such.
         */
        private void collectRoleOverrides() {
            for (Decision d : effective) {
                String raw;
                Role role;
                if (d instanceof Decision.MarkNoop m) {
                    raw = m.externalId();
                    role = Role.NOOP;
                } else if (d instanceof Decision.UnmarkNoop u) {
                    raw = u.externalId();
                    role = Role.TRANSACTION;
                } else {
                    continue;
                }
                String id = resolve(raw);
                if (id == null) {
                    ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                        d.action().wire() + " names an unknown id " + raw));
                    continue;
                }
                roleOverrides.put(id, role);
            }
        }

        /**
         * The row's role: an account profile rule classifies a non-posting, and a decision wins
         * over the profile in either direction (V2-PROPOSAL.md §6.9).
         */
        private Role roleFor(Fact f) {
            Role override = roleOverrides.get(f.externalId());
            if (override != null) {
                return override;
            }
            return config.profiles().isNoop(f.accountRef(), f.rawDescription()) ? Role.NOOP : Role.TRANSACTION;
        }

        // ---- P7: transfer shape and pairing -------------------------------------------------

        private void pairing(List<CurrentFact> all) {
            // A noop row is not a posting: it never shapes, pairs or holds (§6.9).
            List<CurrentFact> currentFacts = all.stream().filter(c -> c.role() != Role.NOOP).toList();
            Map<String, CurrentFact> byId = new HashMap<>();
            for (CurrentFact c : currentFacts) {
                byId.put(c.externalId(), c);
            }
            Set<String> receiptShaped = receiptShapedLegs(currentFacts);

            // Effective pairing decisions, resolved, latest per leg.
            Map<String, Decision> pairingDecision = new HashMap<>();
            for (Decision d : effective) {
                if (d instanceof Decision.MarkExternal m) {
                    String id = resolve(m.externalId());
                    if (id == null) {
                        ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                            "MARK_EXTERNAL names an unknown id " + m.externalId()));
                    } else if (byId.containsKey(id)) {
                        pairingDecision.put(id, d);
                    }
                } else if (d instanceof Decision.Pair p) {
                    String a = resolve(p.legA());
                    String b = resolve(p.legB());
                    if (a == null || b == null) {
                        ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                            "PAIR names an unknown id (" + p.legA() + ", " + p.legB() + ")"));
                    } else {
                        if (byId.containsKey(a)) {
                            pairingDecision.put(a, d);
                        }
                        if (byId.containsKey(b)) {
                            pairingDecision.put(b, d);
                        }
                    }
                } else if (d instanceof Decision.Unpair u) {
                    String a = resolve(u.legA());
                    String b = resolve(u.legB());
                    if (a == null || b == null) {
                        ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                            "UNPAIR names an unknown id (" + u.legA() + ", " + u.legB() + ")"));
                    } else {
                        if (byId.containsKey(a)) {
                            pairingDecision.put(a, d);
                        }
                        if (byId.containsKey(b)) {
                            pairingDecision.put(b, d);
                        }
                    }
                }
            }

            // A person attached these legs to a clearing account (§6.10): a pruned counterparty whose
            // statements are gone. They pair directly, like a clearing pattern, overriding the matcher.
            Map<String, String> attached = new HashMap<>();
            for (Decision d : effective) {
                if (d instanceof Decision.AttachAccount aa) {
                    boolean clearing = config.registry().findAccount(aa.account())
                        .map(a -> a.balanceSource() == BalanceSource.CLEARING).orElse(false);
                    if (!clearing) {
                        ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                            "ATTACH_ACCOUNT names a non-clearing account " + aa.account()));
                        continue;
                    }
                    for (String raw : aa.externalIds()) {
                        String id = resolve(raw);
                        if (id == null || !byId.containsKey(id)) {
                            ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                                "ATTACH_ACCOUNT names an unknown id " + raw));
                            continue;
                        }
                        attached.put(id, aa.account());
                    }
                }
            }

            // Live decision pairs: a PAIR holds only while it is the latest decision for both legs.
            Map<String, TransferRow> rows = new TreeMap<>();            Set<String> livePairLegs = new HashSet<>();
            for (Decision d : effective) {
                if (d instanceof Decision.Pair p) {
                    String a = resolve(p.legA());
                    String b = resolve(p.legB());
                    if (a == null || b == null) {
                        continue;
                    }
                    if (pairingDecision.get(a) == d && pairingDecision.get(b) == d) {
                        Fact fa = byId.get(a).fact();
                        Fact fb = byId.get(b).fact();
                        String tid = uniqueTransferId(rows, mintTransferId(fa, fb), byId.get(a), byId.get(b));
                        rows.put(tid, new TransferRow(tid, a, b, Confidence.MANUAL, "decision", p.n(),
                            transferMethod(fa, fb), p.at(), null));
                        livePairLegs.add(a);
                        livePairLegs.add(b);
                    }
                }
            }

            // Legs whose latest effective decision takes them out of the pool.
            Set<String> outOfPool = new HashSet<>();
            for (Map.Entry<String, Decision> e : pairingDecision.entrySet()) {
                if (e.getValue() instanceof Decision.MarkExternal || e.getValue() instanceof Decision.Unpair) {
                    outOfPool.add(e.getKey());
                }
            }

            // Eligible for the matcher: shaped, undecided, not live-paired, not taken out.
            List<CurrentFact> eligible = new ArrayList<>();
            for (CurrentFact c : currentFacts) {
                String id = c.externalId();
                if (livePairLegs.contains(id) || outOfPool.contains(id)) {
                    continue;
                }
                if (attached.containsKey(id)) {
                    pairClearing(rows, c, attached.get(id));
                    continue;
                }
                // A PAIR that is not live for both legs frees this leg back to the pool, so a leg
                // whose latest decision is a PAIR is still eligible; MARK_EXTERNAL/UNPAIR are not.
                if (!isShaped(c.fact(), receiptShaped)) {
                    continue;
                }
                eligible.add(c);
            }
            eligible.sort(Comparator.comparing((CurrentFact c) -> c.fact().date()).thenComparingLong(c -> c.fact().n()));

            boolean[] paired = new boolean[eligible.size()];
            for (int i = 0; i < eligible.size(); i++) {
                if (paired[i]) {
                    continue;
                }
                CurrentFact f = eligible.get(i);
                if (!isShaped(f.fact(), receiptShaped)) {
                    legState.put(f.externalId(), LegState.EXTERNAL);
                    continue;
                }
                // A clearing pattern names its counterparty: the leg pairs directly with that
                // account — no contra fact, no window, no ambiguity (§6.10).
                String clearing = config.transfers().clearingFor(f.fact().accountRef(), f.fact().rawDescription());
                if (clearing != null) {
                    pairClearing(rows, f, clearing);
                    paired[i] = true;
                    continue;
                }
                int window = config.transfers().windowDays();
                // T1 — receipt. Equal amount, opposite sign, same currency and within the window:
                // receipts recur across accounts and years, so the guard costs nothing on a genuine
                // pair and refuses to shape two unrelated rows on a collision (§9.9.C.3).
                List<Integer> t1 = candidates(eligible, paired, i, (s, g) ->
                    s.fact().receipt() != null && !s.fact().receipt().isBlank()
                        && s.fact().receipt().equals(g.fact().receipt())
                        && !g.fact().accountRef().equals(s.fact().accountRef())
                        && oppositeSign(s.fact().amount(), g.fact().amount())
                        && Math.abs(g.fact().amount()) == Math.abs(s.fact().amount())
                        && currency(g).equals(currency(s))
                        && Math.abs(ChronoUnit.DAYS.between(s.fact().date(), g.fact().date())) <= window);
                if (t1.size() == 1) {
                    pairT1(rows, paired, eligible, i, t1.getFirst(), f);
                    continue;
                }
                if (t1.size() > 1) {
                    legState.put(f.externalId(), LegState.HELD);
                    pairingReview.add(ambiguous(f, t1, eligible));
                    continue;
                }
                // T2 — same day, unique both ways: this leg has exactly one candidate and that
                // candidate has no other same-day suitor. Text is never compared across accounts.
                java.util.function.BiPredicate<CurrentFact, CurrentFact> sameDay = (s, g) ->
                    Math.abs(g.fact().amount()) == Math.abs(s.fact().amount())
                        && oppositeSign(s.fact().amount(), g.fact().amount())
                        && !g.fact().accountRef().equals(s.fact().accountRef())
                        && currency(g).equals(currency(s))
                        && g.fact().date().equals(s.fact().date());
                int t2 = counterpart(eligible, paired, i, sameDay);
                if (t2 >= 0) {
                    pairT2(rows, paired, eligible, i, t2, f, Confidence.HIGH);
                    continue;
                }
                if (t2 == AMBIGUOUS) {
                    legState.put(f.externalId(), LegState.HELD);
                    pairingReview.add(ambiguous(f, candidates(eligible, paired, i, sameDay), eligible));
                    continue;
                }
                // T3 — windowed unique: as T2, for legs that differ in date by at most windowDays.
                java.util.function.BiPredicate<CurrentFact, CurrentFact> windowed = (s, g) ->
                    Math.abs(g.fact().amount()) == Math.abs(s.fact().amount())
                        && oppositeSign(s.fact().amount(), g.fact().amount())
                        && !g.fact().accountRef().equals(s.fact().accountRef())
                        && currency(g).equals(currency(s))
                        && !g.fact().date().equals(s.fact().date())
                        && Math.abs(ChronoUnit.DAYS.between(s.fact().date(), g.fact().date())) <= window;
                int t3 = counterpart(eligible, paired, i, windowed);
                if (t3 >= 0) {
                    pairT2(rows, paired, eligible, i, t3, f, Confidence.HIGH);
                    continue;
                }
                if (t3 == AMBIGUOUS) {
                    legState.put(f.externalId(), LegState.HELD);
                    pairingReview.add(ambiguous(f, candidates(eligible, paired, i, windowed), eligible));
                    continue;
                }
                legState.put(f.externalId(), LegState.HELD);
            }

            // Legs that carry an effective MARK_EXTERNAL / UNPAIR, and every unshaped or
            // decision-paired leg, get their final state here.
            for (CurrentFact c : currentFacts) {
                String id = c.externalId();
                if (livePairLegs.contains(id)) {
                    legState.put(id, LegState.MATCHED);
                } else if (outOfPool.contains(id)) {
                    legState.put(id, LegState.EXTERNAL);
                } else {
                    legState.merge(id, LegState.EXTERNAL, (a, b) -> a);
                }
            }
            for (TransferRow t : rows.values()) {
                transferIdByLeg.put(t.fromLeg(), t.transferId());
                transferIdByLeg.put(t.toLeg(), t.transferId());
            }
            transfers = new ArrayList<>(rows.values());
        }

        private List<Integer> candidates(List<CurrentFact> eligible, boolean[] paired, int self,
                                         java.util.function.BiPredicate<CurrentFact, CurrentFact> filter) {
            CurrentFact s = eligible.get(self);
            List<Integer> out = new ArrayList<>();
            for (int j = 0; j < eligible.size(); j++) {
                if (j == self || paired[j]) {
                    continue;
                }
                if (filter.test(s, eligible.get(j))) {
                    out.add(j);
                }
            }
            return out;
        }

        /** More than one candidate at a tier, or a candidate that has another suitor. */
        private static final int AMBIGUOUS = -2;

        /**
         * The unique counterpart of {@code self}: exactly one candidate, and that candidate's own
         * candidate set is exactly {@code self}. Returns its index, {@code -1} for none or
         * {@link #AMBIGUOUS}. Mutual uniqueness is what stops a leg with two suitors from being
         * paired arbitrarily by the first one processed (§9.9.C.3).
         */
        private int counterpart(List<CurrentFact> eligible, boolean[] paired, int self,
                                java.util.function.BiPredicate<CurrentFact, CurrentFact> filter) {
            List<Integer> mine = candidates(eligible, paired, self, filter);
            if (mine.isEmpty()) {
                return -1;
            }
            if (mine.size() > 1) {
                return AMBIGUOUS;
            }
            int j = mine.getFirst();
            List<Integer> theirs = candidates(eligible, paired, j, filter);
            return theirs.size() == 1 && theirs.getFirst() == self ? j : AMBIGUOUS;
        }

        /** Pair a leg with a clearing account: one real leg, an account side, direction structural. */
        private void pairClearing(Map<String, TransferRow> rows, CurrentFact f, String clearingAccount) {
            Fact real = f.fact();
            String tid = Ids.transferId(real.externalId(), clearingAccount);
            boolean out = real.amount() < 0;
            Rail method = railFor(real);
            rows.put(tid, new TransferRow(tid, out ? real.externalId() : clearingAccount,
                out ? clearingAccount : real.externalId(), Confidence.EXACT, "derived", null,
                method == null ? Rail.BANK_TRANSFER : method, real.ingestedAt(), clearingAccount));
            legState.put(real.externalId(), LegState.MATCHED);
        }

        private void pairT1(Map<String, TransferRow> rows, boolean[] paired, List<CurrentFact> eligible,
                            int i, int j, CurrentFact f) {
            CurrentFact g = eligible.get(j);
            String tid = uniqueTransferId(rows, Ids.transferId(f.fact().receipt()), f, g);
            rows.put(tid, new TransferRow(tid, f.externalId(), g.externalId(), Confidence.EXACT, "derived",
                null, transferMethod(f.fact(), g.fact()), laterIngested(f.fact(), g.fact()), null));
            paired[i] = true;
            paired[j] = true;
            legState.put(f.externalId(), LegState.MATCHED);
            legState.put(g.externalId(), LegState.MATCHED);
        }

        private void pairT2(Map<String, TransferRow> rows, boolean[] paired, List<CurrentFact> eligible,
                            int i, int j, CurrentFact f, Confidence confidence) {
            CurrentFact g = eligible.get(j);
            String tid = uniqueTransferId(rows, mintTransferId(f.fact(), g.fact()), f, g);
            rows.put(tid, new TransferRow(tid, f.externalId(), g.externalId(), confidence, "derived",
                null, transferMethod(f.fact(), g.fact()), laterIngested(f.fact(), g.fact()), null));
            paired[i] = true;
            paired[j] = true;
            legState.put(f.externalId(), LegState.MATCHED);
            legState.put(g.externalId(), LegState.MATCHED);
        }

        private ReviewItem ambiguous(CurrentFact f, List<Integer> candidates, List<CurrentFact> eligible) {
            List<String> ids = new ArrayList<>();
            for (int j : candidates) {
                ids.add(eligible.get(j).externalId());
            }
            Collections.sort(ids);
            String detail = String.join(",", ids);
            return new ReviewItem(f.externalId(), ReviewItem.AMBIGUOUS_TRANSFER, detail,
                Math.abs(f.fact().amount()), f.fact().ingestedAt(),
                Hashes.sha256(ReviewItem.AMBIGUOUS_TRANSFER + "|" + f.externalId() + "|" + detail));
        }

        private String mintTransferId(Fact a, Fact b) {
            if (a.receipt() != null && !a.receipt().isBlank() && a.receipt().equals(b.receipt())) {
                return Ids.transferId(a.receipt());
            }
            return Ids.transferId(a.externalId(), b.externalId());
        }

        /**
         * A receipt is not unique across transfers (§8.3: that is why the date is in the natural
         * key), so two pairs can want the same {@code TRF-<receipt>}. Overwriting would drop a
         * transfer and silently unproject both its legs, so a taken id falls back to the
         * order-independent leg hash. A second collision is a broken invariant, never a guess.
         */
        private String uniqueTransferId(Map<String, TransferRow> rows, String preferred, CurrentFact f,
                                        CurrentFact g) {
            if (!rows.containsKey(preferred)) {
                return preferred;
            }
            String hashed = Ids.transferId(f.externalId(), g.externalId());
            if (!rows.containsKey(hashed)) {
                return hashed;
            }
            throw new IllegalStateException(
                "duplicate transfer id " + hashed + " for legs " + f.externalId() + "/" + g.externalId());
        }

        private String currency(CurrentFact c) {
            Account account = config.registry().account(c.fact().accountRef());
            return account.currency();
        }

        /** The rail method the account's patterns declare for this row, or null (§9.9.C.4). */
        private Rail railFor(Fact f) {
            return config.transfers().railFor(f.accountRef(), f.rawDescription());
        }

        /** The payer leg's rail method, falling back to the payee's and then {@code BANK_TRANSFER}. */
        private Rail transferMethod(Fact a, Fact b) {
            Fact payer = a.amount() < 0 ? a : b;
            Fact payee = payer == a ? b : a;
            Rail method = railFor(payer);
            if (method != null) {
                return method;
            }
            method = railFor(payee);
            return method != null ? method : Rail.BANK_TRANSFER;
        }

        /**
         * The receipts that shape a leg: a non-null receipt shared with a leg in another account
         * that is a <b>plausible counterpart</b> — opposite sign, equal {@code |amount|}, the same
         * currency and dates within {@code windowDays} (§9.9.C.2). Receipt numbers are not globally
         * unique (the same ING receipt recurs across accounts and years), so a collision must not
         * shape two unrelated rows.
         */
        /**
         * The ids of current facts that share a receipt with a plausible counterpart (§9.9.C). A
         * receipt alone is not enough: ING receipt numbers are not globally unique, so a colliding
         * receipt (a card purchase and a loan repayment years apart) must not shape an unrelated row.
         * Only the facts that actually participate in a plausible pair are returned.
         */
        private Set<String> receiptShapedLegs(List<CurrentFact> currentFacts) {
            Map<String, List<Fact>> byReceipt = new TreeMap<>();
            for (CurrentFact c : currentFacts) {
                String r = c.fact().receipt();
                if (r != null && !r.isBlank()) {
                    byReceipt.computeIfAbsent(r, k -> new ArrayList<>()).add(c.fact());
                }
            }
            Set<String> shaped = new HashSet<>();
            byReceipt.values().forEach(group -> {
                for (int i = 0; i < group.size(); i++) {
                    for (int j = i + 1; j < group.size(); j++) {
                        if (plausibleCounterpart(group.get(i), group.get(j))) {
                            shaped.add(group.get(i).externalId());
                            shaped.add(group.get(j).externalId());
                        }
                    }
                }
            });
            return shaped;
        }

        private boolean plausibleCounterpart(Fact a, Fact b) {
            return !a.accountRef().equals(b.accountRef())
                && oppositeSign(a.amount(), b.amount())
                && Math.abs(a.amount()) == Math.abs(b.amount())
                && currencyOf(a).equals(currencyOf(b))
                && Math.abs(ChronoUnit.DAYS.between(a.date(), b.date())) <= config.transfers().windowDays();
        }

        private String currencyOf(Fact f) {
            return config.registry().account(f.accountRef()).currency();
        }

        private boolean isShaped(Fact f, Set<String> receiptShaped) {
            if (config.transfers().isTransferShaped(f.accountRef(), f.rawDescription())) {
                return true;
            }
            return f.receipt() != null && receiptShaped.contains(f.externalId());
        }

        private Map<String, CurrentFact> applyPairing(Map<String, CurrentFact> currentMap) {
            Map<String, CurrentFact> out = new LinkedHashMap<>();
            currentMap.forEach((id, c) -> out.put(id, new CurrentFact(c.fact(), c.role(), c.rail(),
                legState.getOrDefault(id, LegState.EXTERNAL), transferIdByLeg.get(id), null,
                CategoryOrigin.NONE, null, null)));
            return out;
        }

        // ---- P9: category -------------------------------------------------------------------

        private Map<String, CategoryRow> categories(Map<String, CurrentFact> currentMap) {
            Map<String, CategoryRow> out = new TreeMap<>();
            // Latest effective PIN/UNPIN naming each resolved id.
            Map<String, Decision> pinDecision = new HashMap<>();
            for (Decision d : effective) {
                if (d instanceof Decision.Pin p) {
                    boolean bad = false;
                    for (String raw : p.externalIds()) {
                        String id = resolve(raw);
                        if (id == null) {
                            bad = true;
                            continue;
                        }
                        if (!config.categories().isDeclared(p.category()) || RuleSet.isReserved(p.category())) {
                            bad = true;
                            continue;
                        }
                        if (currentMap.containsKey(id) && currentMap.get(id).matched()) {
                            bad = true; // a pin that can never win is a lie (§9.9.E.4)
                            continue;
                        }
                        pinDecision.put(id, d);
                    }
                    if (bad) {
                        ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                            "PIN could not apply (" + String.join(",", p.externalIds()) + " -> " + p.category() + ")"));
                    }
                } else if (d instanceof Decision.Unpin u) {
                    for (String raw : u.externalIds()) {
                        String id = resolve(raw);
                        if (id == null) {
                            ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                                "UNPIN names an unknown id " + raw));
                        } else {
                            pinDecision.put(id, d);
                        }
                    }
                }
            }
            for (CurrentFact c : currentMap.values()) {
                String id = c.externalId();
                if (c.matched()) {
                    out.put(id, new CategoryRow(id, RuleSet.TRANSFER, CategoryOrigin.STRUCTURAL, null));
                    continue;
                }
                Decision pin = pinDecision.get(id);
                if (pin instanceof Decision.Pin p) {
                    out.put(id, new CategoryRow(id, p.category(), CategoryOrigin.PIN, null));
                    continue;
                }
                RuleSubject subject = RuleSubject.of(id, c.fact().accountRef(), c.fact().rawDescription(),
                    c.fact().amount());
                Optional<Rule> rule = config.categories().firstMatch(subject);
                if (rule.isPresent()) {
                    out.put(id, new CategoryRow(id, rule.get().category(), CategoryOrigin.RULE,
                        rule.get().where()));
                } else {
                    out.put(id, new CategoryRow(id, RuleSet.UNCATEGORIZED, CategoryOrigin.NONE, null));
                }
            }
            return out;
        }

        private List<PinRow> pins() {
            Map<String, PinRow> out = new TreeMap<>();
            Map<String, Decision.Pin> latest = new HashMap<>();
            for (Decision d : effective) {
                if (d instanceof Decision.Pin p) {
                    for (String raw : p.externalIds()) {
                        String id = resolve(raw);
                        if (id != null) {
                            latest.put(id, p);
                        }
                    }
                }
            }
            latest.forEach((id, p) -> out.put(id, new PinRow(id, p.category(), p.n(), p.user(), p.comment())));
            return new ArrayList<>(out.values());
        }

        /**
         * Every effective {@code NOTE}, ids resolved through the supersession map (V2-PROPOSAL.md
         * §6.2). Unlike a pin, a note accumulates: one row per decision, ordered by {@code n}, so
         * the thread is preserved. A note naming an unknown id is ineffective, never silently
         * dropped.
         */
        private List<NoteRow> notes() {
            List<NoteRow> out = new ArrayList<>();
            for (Decision d : effective) {
                if (d instanceof Decision.Note note) {
                    String id = resolve(note.externalId());
                    if (id == null) {
                        ineffective.add(new IneffectiveDecision(d.n(), Action.NOTE.wire(),
                            "unknown externalId"));
                        continue;
                    }
                    out.add(new NoteRow(id, note.text(), note.n(), note.user(), note.at()));
                }
            }
            out.sort(Comparator.comparingLong(NoteRow::decisionN));
            return out;
        }

        private CurrentFact withCategory(CurrentFact c, CategoryRow row) {
            if (row == null) {
                return c;
            }
            return new CurrentFact(c.fact(), c.role(), c.rail(), c.leg(), c.transferId(), row.category(),
                row.origin(), row.ruleId(), c.stateHash());
        }

        /** Fill in the row's content hash, once its category and pairing are final (§9.4). */
        private CurrentFact withStateHash(CurrentFact c) {
            return c.withStateHash(StateHash.forRow(c, currency(c)));
        }

        // ---- P10: commitments (a sibling of the category stage; V2-COMMITMENTS-PLAN.md §2) -----

        /**
         * Fold the seven commitment actions, detect and match over the same current facts the
         * category stage sees, and assemble the commitment tables and their review items. The stage
         * is a sibling of category: it reads no category output and category reads none of it, so
         * their order is incidental (§2.2). Curation is decisions; candidates, rules, occurrences,
         * prices, arrears and dormancy are derived. {@code asOf} is the only notion of "now".
         */
        private void commitments() {
            LocalDate asOfDate = asOf.atZone(ZoneOffset.UTC).toLocalDate();

            // The posted frontier per account at asOf: dormancy is judged against it, never a wall
            // clock, so a closed account's silence is not read as an ending (§2.3.7).
            Map<String, LocalDate> frontier = new TreeMap<>();
            for (CurrentFact c : current) {
                if (!c.fact().date().isAfter(asOfDate)) {
                    frontier.merge(c.fact().accountRef(), c.fact().date(),
                        (a, b) -> a.isAfter(b) ? a : b);
                }
            }

            // The seven actions folded in n order. A declare sets or replaces the commitment and
            // revives a retired one (family inverse); a retire marks it ended. A note and a settle
            // may name a retired commitment — both are conclusions about the past — but a pin may
            // not: a pin naming a retired or unknown commitment is ineffective and visible. Ids a
            // pin names resolve through the supersession map; commitment ids are decision-local.
            Map<String, DeclaredCommitment> declared = new TreeMap<>();
            Set<String> ignored = new TreeSet<>();
            Map<String, Decision.PinCommitment> pinByFact = new TreeMap<>();
            List<CommitmentNote> notes = new ArrayList<>();
            Map<String, TreeMap<LocalDate, Long>> settles = new TreeMap<>();
            for (Decision d : effective) {
                if (d instanceof Decision.DeclareCommitment dc) {
                    String bad = badMatch(dc);
                    if (bad == null) {
                        declared.put(dc.commitmentId(), new DeclaredCommitment(dc, null, null));
                    } else {
                        // The hub prechecks rules (§2.2); a line from another writer must not
                        // fail the derivation — the declaration is ineffective and visible, and
                        // an earlier or later good declaration of the id still stands.
                        ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                            "DECLARE_COMMITMENT rule does not compile: " + bad));
                    }
                } else if (d instanceof Decision.RetireCommitment rc) {
                    DeclaredCommitment existing = declared.get(rc.commitmentId());
                    if (existing == null) {
                        ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                            "RETIRE_COMMITMENT names an undeclared commitment " + rc.commitmentId()));
                    } else {
                        declared.put(rc.commitmentId(), existing.ended(rc.n(), rc.endedAt()));
                    }
                } else if (d instanceof Decision.IgnoreRecurring ir) {
                    ignored.add(ir.candidate());
                } else if (d instanceof Decision.PinCommitment pc) {
                    boolean bad = false;
                    List<String> ids = new ArrayList<>();
                    for (String raw : pc.externalIds()) {
                        String id = resolve(raw);
                        if (id == null) {
                            bad = true;
                        } else {
                            ids.add(id);
                        }
                    }
                    DeclaredCommitment target = declared.get(pc.commitmentId());
                    if (target == null || target.retired()) {
                        bad = true; // a pin to a retired or unknown commitment is a visible no-op
                    }
                    if (bad) {
                        ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                            "PIN_COMMITMENT could not apply ("
                                + String.join(",", pc.externalIds()) + " -> " + pc.commitmentId() + ")"));
                    } else {
                        for (String id : ids) {
                            pinByFact.put(id, pc);
                        }
                    }
                } else if (d instanceof Decision.UnpinCommitment uc) {
                    for (String raw : uc.externalIds()) {
                        String id = resolve(raw);
                        if (id == null) {
                            ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                                "UNPIN_COMMITMENT names an unknown id " + raw));
                        } else {
                            pinByFact.remove(id); // harmless when the fact was not pinned
                        }
                    }
                } else if (d instanceof Decision.NoteCommitment nc) {
                    if (!declared.containsKey(nc.commitmentId())) {
                        ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                            "NOTE_COMMITMENT names an undeclared commitment " + nc.commitmentId()));
                    } else {
                        notes.add(new CommitmentNote(nc.n(), nc.commitmentId(), nc.text(),
                            nc.user(), nc.at()));
                    }
                } else if (d instanceof Decision.SettleOccurrence so) {
                    if (!declared.containsKey(so.commitmentId())) {
                        ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                            "SETTLE_OCCURRENCE names an undeclared commitment " + so.commitmentId()));
                    } else {
                        TreeMap<LocalDate, Long> byDate =
                            settles.computeIfAbsent(so.commitmentId(), k -> new TreeMap<>());
                        for (LocalDate due : so.dueDates()) {
                            byDate.merge(due, d.n(), Math::max);
                        }
                    }
                }
            }
            commitmentIds.addAll(declared.keySet());

            // Rules come only from the latest effective declaration, in its own match order; a
            // blank account scope is "any account", and an identical match/scope pair collapses so
            // the derived table's key cannot collide (§2.2, §2.7).
            Map<String, List<CommitmentRule>> rulesById = new TreeMap<>();
            Map<String, List<CommitmentRules.CompiledRule>> compiledById = new TreeMap<>();
            for (Map.Entry<String, DeclaredCommitment> e : declared.entrySet()) {
                LinkedHashMap<String, CommitmentRule> dedup = new LinkedHashMap<>();
                for (Decision.Match m : e.getValue().matches()) {
                    String account = m.account() == null || m.account().isBlank() ? null : m.account();
                    String key = m.match() + '\u0000' + account;
                    dedup.putIfAbsent(key, new CommitmentRule(e.getKey(), m.match(), account,
                        e.getValue().declaredN()));
                }
                List<CommitmentRule> rules = List.copyOf(dedup.values());
                rulesById.put(e.getKey(), rules);
                List<CommitmentRules.CompiledRule> compiled = new ArrayList<>(rules.size());
                for (CommitmentRule rule : rules) {
                    compiled.add(CommitmentRules.compile(rule));
                }
                compiledById.put(e.getKey(), compiled);
            }

            // Candidates over the same current facts, grouped by the frozen stem. A candidate is
            // suppressed by an effective ignore, a declaration that named it, or a declaration
            // whose rules cover every fact of its group (§2.3.8); detection itself is Stage 1.
            List<Commitment> detected = Commitments.detect(current, asOf);
            Map<String, List<CurrentFact>> groups = new TreeMap<>();
            for (CurrentFact c : current) {
                if (c.role() == Role.NOOP) {
                    continue;
                }
                String key = MerchantStem.stem(c.fact().rawDescription());
                if (!key.isEmpty()) {
                    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
                    newestFactByStem.merge(key, c.fact().n(), Math::max);
                }
            }
            for (Commitment c : detected) {
                candidateKeys.add(c.candidateKey());
            }
            Set<String> namedCandidates = new TreeSet<>();
            for (DeclaredCommitment d : declared.values()) {
                if (d.fromCandidate() != null && !d.fromCandidate().isBlank()) {
                    namedCandidates.add(d.fromCandidate());
                }
            }
            List<Commitment> candidates = new ArrayList<>();
            for (Commitment candidate : detected) {
                String key = candidate.candidateKey();
                if (ignored.contains(key) || namedCandidates.contains(key)
                    || coveredByDeclaration(compiledById, groups.getOrDefault(key, List.of()))) {
                    continue;
                }
                candidates.add(candidate);
            }

            // Matching over the declared commitments. A regular commitment with no anchor has no
            // schedule to generate and stays a registry row without occurrences (the writer accepts
            // an optional anchor; the hub's dialog always sends one).
            List<Commitment> tracked = new ArrayList<>();
            for (DeclaredCommitment d : declared.values()) {
                if (d.cadence().regular() && d.anchor() == null) {
                    continue;
                }
                tracked.add(d.row());
            }
            List<CommitmentRule> allRules = new ArrayList<>();
            for (List<CommitmentRule> rules : rulesById.values()) {
                allRules.addAll(rules);
            }
            List<CommitmentPin> pins = new ArrayList<>();
            for (Map.Entry<String, Decision.PinCommitment> e : pinByFact.entrySet()) {
                pins.add(new CommitmentPin(e.getKey(), e.getValue().commitmentId()));
            }
            List<CommitmentSettle> settleList = new ArrayList<>();
            for (Map.Entry<String, TreeMap<LocalDate, Long>> e : settles.entrySet()) {
                for (Map.Entry<LocalDate, Long> due : e.getValue().entrySet()) {
                    settleList.add(new CommitmentSettle(e.getKey(), due.getKey(), due.getValue()));
                }
            }
            CommitmentMatch match = Commitments.match(tracked, allRules, current, pins, settleList, asOf);

            Map<String, List<CommitmentOccurrence>> occurrences = new TreeMap<>();
            for (CommitmentOccurrence o : match.occurrences()) {
                occurrences.computeIfAbsent(o.commitmentId(), k -> new ArrayList<>()).add(o);
            }
            Map<String, CommitmentArrears> arrears = new TreeMap<>();
            for (CommitmentArrears a : match.arrears()) {
                arrears.put(a.commitmentId(), a);
            }
            Map<String, CurrentFact> byId = currentById();

            // Assemble the rows: candidates as detected, declared rows with the folded faces and
            // the cost figures their matches imply. Every list is ordered by id (§4).
            List<Commitment> rows = new ArrayList<>(candidates);
            List<ReviewItem> items = new ArrayList<>();
            for (Commitment candidate : candidates) {
                if (candidate.status() != CommitmentStatus.ENDED) {
                    items.add(suspected(candidate, groups.getOrDefault(candidate.candidateKey(), List.of())));
                }
            }
            for (DeclaredCommitment d : declared.values()) {
                List<CommitmentOccurrence> own = occurrences.getOrDefault(d.commitmentId(), List.of());
                CommitmentArrears behind = arrears.getOrDefault(d.commitmentId(),
                    new CommitmentArrears(d.commitmentId(), 0, 0, false));
                for (CommitmentOccurrence o : own) {
                    if (o.matchedExternalId() != null) {
                        Fact f = latestById.get(o.matchedExternalId());
                        if (f != null) {
                            newestFactByCommitment.merge(d.commitmentId(), f.n(), Math::max);
                        }
                    }
                }
                Dormancy dormancy = dormancy(d, own, byId, frontier, rulesById.get(d.commitmentId()));
                rows.add(declaredRow(d, own, behind, dormancy));
                if (dormancy != null) {
                    items.add(dormantItem(d, dormancy, own, byId));
                }
                if (behind.count() > 0) {
                    items.add(arrearsItem(d, behind, own, byId));
                }
            }
            rows.sort(Comparator.comparing(Commitment::commitmentId));
            commitmentRows = rows;
            commitmentRules = List.copyOf(allRules);
            commitmentOccurrences = match.occurrences();
            commitmentNotes = List.copyOf(notes);
            commitmentReview = items;
        }

        /**
         * The first match of a declaration that does not compile, or null when the rule set is
         * usable. Compilation goes through the matcher's own convention, so the fold and the
         * matcher can never disagree about what a valid rule is (§2.2); the hub's 422 precheck is
         * the first line, this is the defence for a line from another writer.
         */
        private static String badMatch(Decision.DeclareCommitment dc) {
            for (Decision.Match m : dc.matches()) {
                String account = m.account() == null || m.account().isBlank() ? null : m.account();
                try {
                    CommitmentRules.compile(new CommitmentRule(dc.commitmentId(), m.match(),
                        account, dc.n()));
                } catch (IllegalArgumentException e) {
                    return m.match();
                }
            }
            return null;
        }

        /** True when some declaration's rules match every fact of a candidate's group (§2.3.8). */
        private static boolean coveredByDeclaration(
                Map<String, List<CommitmentRules.CompiledRule>> compiledById, List<CurrentFact> group) {
            if (group.isEmpty()) {
                return false;
            }
            for (List<CommitmentRules.CompiledRule> rules : compiledById.values()) {
                if (rules.isEmpty()) {
                    continue;
                }
                boolean all = true;
                for (CurrentFact fact : group) {
                    boolean any = false;
                    for (CommitmentRules.CompiledRule rule : rules) {
                        if (rule.matches(fact)) {
                            any = true;
                            break;
                        }
                    }
                    if (!any) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The dormancy question (§2.1, §2.8): a tracked, non-retired, regular commitment whose most
         * recent satisfied occurrence — a matched fact or a settle, both engagement — is more than
         * one cadence plus tolerance behind the posted frontier of the accounts it matched on (or,
         * before any match, the accounts its rules name). A commitment that never matched is not
         * dormant: it is in arrears. Silence is judged against the frontier, never a wall clock; an
         * irregular or retired commitment is never dormant.
         */
        private Dormancy dormancy(DeclaredCommitment d, List<CommitmentOccurrence> occurrences,
                                  Map<String, CurrentFact> byId, Map<String, LocalDate> frontier,
                                  List<CommitmentRule> rules) {
            if (d.retired() || !d.cadence().regular()) {
                return null;
            }
            LocalDate last = null;
            for (CommitmentOccurrence o : occurrences) {
                if (o.matchedExternalId() != null || o.status() == OccurrenceStatus.SETTLED) {
                    if (last == null || o.dueDate().isAfter(last)) {
                        last = o.dueDate();
                    }
                }
            }
            if (last == null) {
                return null;
            }
            Set<String> accounts = new TreeSet<>();
            for (CommitmentOccurrence o : occurrences) {
                if (o.matchedExternalId() == null) {
                    continue;
                }
                CurrentFact fact = byId.get(o.matchedExternalId());
                if (fact != null) {
                    accounts.add(fact.fact().accountRef());
                }
            }
            if (accounts.isEmpty()) {
                for (CommitmentRule rule : rules) {
                    if (rule.accountRef() != null && !rule.accountRef().isBlank()) {
                        accounts.add(rule.accountRef());
                    }
                }
            }
            LocalDate newest = null;
            for (String account : accounts) {
                LocalDate date = frontier.get(account);
                if (date != null && (newest == null || date.isAfter(newest))) {
                    newest = date;
                }
            }
            if (newest == null) {
                return null;
            }
            long silent = ChronoUnit.DAYS.between(last, newest);
            if (silent <= d.cadence().days() + Commitments.tolerance(d.cadence().days())) {
                return null;
            }
            return new Dormancy(last, newest);
        }

        /** A declared commitment's derived row: the folded faces plus the cost its matches imply. */
        private Commitment declaredRow(DeclaredCommitment d, List<CommitmentOccurrence> occurrences,
                                       CommitmentArrears arrears, Dormancy dormancy) {
            List<CommitmentOccurrence> observed = new ArrayList<>();
            for (CommitmentOccurrence o : occurrences) {
                if (o.matchedExternalId() != null) {
                    observed.add(o);
                }
            }
            List<Commitment.PriceStep> steps = new ArrayList<>();
            long cost = 0;
            for (int i = 0; i < observed.size(); i++) {
                CommitmentOccurrence o = observed.get(i);
                long amount = o.amount() == null ? 0 : o.amount();
                cost += amount;
                if (i == 0) {
                    steps.add(new Commitment.PriceStep(o.matchedDate(), amount, null, null));
                } else {
                    long previous = observed.get(i - 1).amount();
                    if (Commitments.isStep(previous, amount)) {
                        steps.add(new Commitment.PriceStep(o.matchedDate(), amount, previous,
                            Commitments.changePct(previous, amount)));
                    }
                }
            }
            Commitment.PriceStep lastStep = steps.size() > 1 ? steps.getLast() : null;
            Long declared = signedAmount(d);
            Long current = observed.isEmpty() ? declared : observed.getLast().amount();
            CommitmentStatus status = d.retired() ? CommitmentStatus.ENDED
                : dormancy != null ? CommitmentStatus.DORMANT : CommitmentStatus.ACTIVE;
            LocalDate first = observed.isEmpty() ? d.anchor() : observed.getFirst().dueDate();
            LocalDate last = observed.isEmpty() ? d.anchor() : observed.getLast().dueDate();
            return new Commitment(
                d.commitmentId(),
                null,
                d.name(),
                CommitmentOrigin.DECLARED,
                d.direction(),
                d.cadence(),
                d.amountKind(),
                d.kind(),
                status,
                first,
                last,
                d.anchor(),
                current,
                lastStep == null ? null : lastStep.previousAmount(),
                lastStep == null ? null : lastStep.changePct(),
                lastStep == null ? null : lastStep.date(),
                steps,
                cost,
                annualised(d, observed, current),
                observed.size(),
                regularity(d.cadence(), observed),
                d.amountKind() == AmountKind.VARIABLE,
                arrears.count(),
                arrears.amount(),
                d.declaredN(),
                d.retiredN(),
                d.endedAt());
        }

        /**
         * The annualised figure (§2.4): the cadence factor times the current amount (a variable
         * commitment's trailing median instead); an irregular commitment has no cadence, so it is
         * the trailing twelve-month total.
         */
        private Long annualised(DeclaredCommitment d, List<CommitmentOccurrence> observed, Long current) {
            if (d.cadence() == Cadence.IRREGULAR) {
                LocalDate from = asOf.atZone(ZoneOffset.UTC).toLocalDate().minusMonths(12);
                LocalDate at = asOf.atZone(ZoneOffset.UTC).toLocalDate();
                long total = 0;
                for (CommitmentOccurrence o : observed) {
                    if (!o.dueDate().isBefore(from) && !o.dueDate().isAfter(at)) {
                        total += o.amount() == null ? 0 : o.amount();
                    }
                }
                return total;
            }
            if (current == null) {
                return null;
            }
            long basis = current;
            if (d.amountKind() == AmountKind.VARIABLE && !observed.isEmpty()) {
                List<Long> amounts = new ArrayList<>();
                int from = Math.max(0, observed.size() - Commitments.TRAILING_WINDOW);
                for (int i = from; i < observed.size(); i++) {
                    amounts.add(observed.get(i).amount());
                }
                basis = Commitments.amountMedian(amounts);
            }
            return (long) Commitments.perYear(d.cadence()) * basis;
        }

        /** The observed regularity of a declared commitment: in-tolerance gaps ÷ gaps (§2.3.4). */
        private Double regularity(Cadence cadence, List<CommitmentOccurrence> observed) {
            if (!cadence.regular()) {
                return null;
            }
            List<LocalDate> dates = new ArrayList<>();
            for (CommitmentOccurrence o : observed) {
                if (o.windowStart() != null) {
                    dates.add(o.dueDate());
                }
            }
            if (dates.size() < 2) {
                return null;
            }
            long within = 0;
            for (int i = 1; i < dates.size(); i++) {
                long gap = ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i));
                if (Math.abs(gap - cadence.days()) <= Commitments.tolerance(cadence.days())) {
                    within++;
                }
            }
            return (double) within / (dates.size() - 1);
        }

        /** One candidate's review item: subject the grouping stem, the detail a person reads (§2.8). */
        private ReviewItem suspected(Commitment candidate, List<CurrentFact> group) {
            String detail = candidate.cadence().wire() + " \u00b7 " + candidate.occurrenceCount()
                + "\u00d7 \u00b7 " + candidate.firstDate() + "\u2192" + candidate.lastDate()
                + " \u00b7 last " + candidate.currentAmount()
                + (candidate.previousAmount() == null ? "" : " (was " + candidate.previousAmount() + ")")
                + " \u00b7 regularity " + candidate.regularity();
            Instant opened = Instant.EPOCH;
            for (CurrentFact c : group) {
                if (c.fact().ingestedAt().isAfter(opened)) {
                    opened = c.fact().ingestedAt();
                }
            }
            return new ReviewItem(candidate.candidateKey(), ReviewItem.SUSPECTED_RECURRING, detail,
                candidate.currentAmount() == null ? null : Math.abs(candidate.currentAmount()),
                opened,
                Hashes.sha256(ReviewItem.SUSPECTED_RECURRING + "|" + candidate.candidateKey() + "|" + detail));
        }

        private ReviewItem dormantItem(DeclaredCommitment d, Dormancy dormancy,
                                       List<CommitmentOccurrence> occurrences, Map<String, CurrentFact> byId) {
            String detail = "no charge since " + dormancy.since() + " (frontier " + dormancy.frontier() + ")";
            return new ReviewItem(d.commitmentId(), ReviewItem.DORMANT_COMMITMENT, detail,
                commitmentStake(d, occurrences), commitmentOpenedAt(d, occurrences, byId),
                Hashes.sha256(ReviewItem.DORMANT_COMMITMENT + "|" + d.commitmentId() + "|" + detail));
        }

        private ReviewItem arrearsItem(DeclaredCommitment d, CommitmentArrears arrears,
                                       List<CommitmentOccurrence> occurrences, Map<String, CurrentFact> byId) {
            String detail = arrears.count() + (arrears.count() == 1
                ? " occurrence behind, total " : " occurrences behind, total ") + arrears.amount();
            return new ReviewItem(d.commitmentId(), ReviewItem.COMMITMENT_ARREARS, detail,
                Math.abs(arrears.amount()), commitmentOpenedAt(d, occurrences, byId),
                Hashes.sha256(ReviewItem.COMMITMENT_ARREARS + "|" + d.commitmentId() + "|" + detail));
        }

        /** The commitment's latest known amount as a positive stake, or null when none is known. */
        private Long commitmentStake(DeclaredCommitment d, List<CommitmentOccurrence> occurrences) {
            for (int i = occurrences.size() - 1; i >= 0; i--) {
                CommitmentOccurrence o = occurrences.get(i);
                if (o.matchedExternalId() != null && o.amount() != null) {
                    return Math.abs(o.amount());
                }
            }
            Long declared = signedAmount(d);
            return declared == null ? null : Math.abs(declared);
        }

        /**
         * The item's age anchor: the newest ingest time among the commitment's matched facts, or the
         * declaring decision's instant when it has none — a never-paid commitment still ages from
         * its declaration.
         */
        private Instant commitmentOpenedAt(DeclaredCommitment d, List<CommitmentOccurrence> occurrences,
                                           Map<String, CurrentFact> byId) {
            Instant newest = null;
            for (CommitmentOccurrence o : occurrences) {
                if (o.matchedExternalId() == null) {
                    continue;
                }
                CurrentFact fact = byId.get(o.matchedExternalId());
                if (fact != null && (newest == null || fact.fact().ingestedAt().isAfter(newest))) {
                    newest = fact.fact().ingestedAt();
                }
            }
            return newest != null ? newest : decisionAt(d.declaredN());
        }

        /** The declaration amount signed by the direction; the wire amount is a magnitude. */
        private static Long signedAmount(DeclaredCommitment d) {
            if (d.amount() == null) {
                return null;
            }
            long magnitude = Math.abs(d.amount());
            return Commitment.OUT.equals(d.direction()) ? -magnitude : magnitude;
        }

        // ---- P8: pending settlement and staleness (V2-PROPOSAL.md §9.9.D) --------------------

        /**
         * Settlement is resolved before pairing, because a pending authorisation is not yet
         * matchable. A pending row shares its account, currency and merchant stem with the row
         * that settles it, its date is within the account's settlement window, and — per §12.3 and
         * the adapters' normalised sign — the amount is the <em>same</em> signed figure.
         */
        private List<PendingRow> pendingSettlement() {
            Map<String, String> forced = new TreeMap<>();
            for (Decision d : effective) {
                if (d instanceof Decision.Settle s) {
                    String pid = resolve(s.pendingId());
                    String qid = resolve(s.postedId());
                    if (pid == null || qid == null) {
                        ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                            "SETTLE names an unknown id (" + s.pendingId() + " -> " + s.postedId() + ")"));
                    } else {
                        forced.put(pid, qid);
                    }
                }
            }
            Set<String> currentIds = new HashSet<>();
            Map<String, java.time.LocalDate> frontier = new TreeMap<>();
            for (CurrentFact c : current) {
                currentIds.add(c.externalId());
                frontier.merge(c.fact().accountRef(), c.fact().date(),
                    (a, b) -> a.isAfter(b) ? a : b);
            }

            List<PendingRow> out = new ArrayList<>();
            for (String id : new TreeSet<>(factsById.keySet())) {
                if (supersession.containsKey(id)) {
                    continue;
                }
                Fact f = latestById.get(id);
                if (f.observation() != Observation.PENDING) {
                    continue;
                }
                int window = config.registry().account(f.accountRef()).settlementWindowDays();
                String currency = config.registry().account(f.accountRef()).currency();
                List<String> candidates = new ArrayList<>();
                for (CurrentFact c : current) {
                    Fact g = c.fact();
                    if (!g.accountRef().equals(f.accountRef())
                        || g.date().isBefore(f.date())
                        || ChronoUnit.DAYS.between(f.date(), g.date()) > window
                        || oppositeSign(f.amount(), g.amount())
                        || Math.abs(g.amount() - f.amount()) > config.transfers().amountTolerance()
                        || !config.registry().account(g.accountRef()).currency().equals(currency)
                        || !MerchantStem.similar(f.rawDescription(), g.rawDescription(),
                            config.transfers().restatementOverlap())) {
                        continue;
                    }
                    candidates.add(g.externalId());
                }
                Collections.sort(candidates);

                String force = forced.get(id);
                if (force != null && currentIds.contains(force)) {
                    out.add(new PendingRow(f, force, PendingState.SETTLED));
                } else if (candidates.size() == 1) {
                    out.add(new PendingRow(f, candidates.getFirst(), PendingState.SETTLED));
                } else if (candidates.size() > 1) {
                    out.add(new PendingRow(f, null, PendingState.OPEN));
                    pendingReview.add(new ReviewItem(id, ReviewItem.AMBIGUOUS_SETTLEMENT,
                        String.join(",", candidates), Math.abs(f.amount()), f.ingestedAt(),
                        Hashes.sha256(ReviewItem.AMBIGUOUS_SETTLEMENT + "|" + id + "|" + String.join(",", candidates))));
                } else {
                    java.time.LocalDate newest = frontier.get(f.accountRef());
                    if (newest != null && ChronoUnit.DAYS.between(f.date(), newest) > window) {
                        out.add(new PendingRow(f, null, PendingState.STALE));
                        pendingReview.add(new ReviewItem(id, ReviewItem.STALE_PENDING,
                            f.accountRef() + " " + f.date(), Math.abs(f.amount()), f.ingestedAt(),
                            Hashes.sha256(ReviewItem.STALE_PENDING + "|" + id)));
                    } else {
                        out.add(new PendingRow(f, null, PendingState.OPEN));
                    }
                }
            }
            return out;
        }

        // ---- P11: review items --------------------------------------------------------------

        private List<ReviewItem> reviewItems() {
            List<ReviewItem> items = new ArrayList<>(pairingReview);
            items.addAll(pendingReview);
            items.addAll(balanceReview());
            items.addAll(commitmentReview);
            Map<String, Long> newestFactBySubject = new TreeMap<>();
            Map<String, Long> newestFactByAccount = new TreeMap<>();
            for (Fact f : facts) {
                newestFactByAccount.merge(f.accountRef(), f.n(), Math::max);
                String subject = resolve(f.externalId());
                if (subject != null) {
                    newestFactBySubject.merge(subject, f.n(), Math::max);
                }
            }

            // POTENTIAL_DUP and RESTATEMENT over current posted facts. Both are pairwise relations,
            // so take the connected components: one review item per cluster, keyed on (subject,
            // kind), listing every member. Pairwise items would repeat a subject whenever three
            // identical rows land on one day, which the derived table's primary key rejects.
            Map<String, CurrentFact> byId = currentById();
            List<CurrentFact> currentList = new ArrayList<>(byId.values());
            currentList.sort(Comparator.comparing((CurrentFact c) -> c.fact().accountRef())
                .thenComparing(c -> c.fact().date()).thenComparingLong(c -> c.fact().n()));
            int[] dupParent = components(currentList.size());
            int[] restParent = components(currentList.size());
            // Both predicates require the same (account, date) pair, and currentList is sorted by
            // exactly that, so compare only inside each contiguous run. The cost becomes the size of
            // a single day on one account, not every fact against every other.
            for (int start = 0; start < currentList.size(); ) {
                CurrentFact first = currentList.get(start);
                int end = start + 1;
                while (end < currentList.size()
                    && currentList.get(end).fact().accountRef().equals(first.fact().accountRef())
                    && currentList.get(end).fact().date().equals(first.fact().date())) {
                    end++;
                }
                for (int i = start; i < end; i++) {
                    for (int j = i + 1; j < end; j++) {
                        CurrentFact a = currentList.get(i);
                        CurrentFact b = currentList.get(j);
                        if (a.matched() || b.matched()) {
                            continue;
                        }
                        boolean sameSign = (a.fact().amount() < 0) == (b.fact().amount() < 0);
                        boolean stemEqual = MerchantStem.stem(a.fact().rawDescription())
                            .equals(MerchantStem.stem(b.fact().rawDescription()));
                        long delta = Math.abs(a.fact().amount() - b.fact().amount());
                        if (sameSign && stemEqual && delta <= config.transfers().dupTolerance()) {
                            union(dupParent, i, j);
                        }
                        boolean sameAmount = a.fact().amount() == b.fact().amount();
                        boolean similar = MerchantStem.restatement(a.fact().rawDescription(),
                            b.fact().rawDescription(), config.transfers().restatementOverlap());
                        if (sameAmount && similar) {
                            union(restParent, i, j);
                        }
                    }
                }
                start = end;
            }
            addClusters(items, byId, currentList, dupParent, ReviewItem.POTENTIAL_DUP);
            addClusters(items, byId, currentList, restParent, ReviewItem.RESTATEMENT);

            // UNMATCHED_LEG: a shaped HELD leg older than holdWindowDays, measured on asOf.
            for (CurrentFact c : byId.values()) {
                if (c.leg() != LegState.HELD) {
                    continue;
                }
                long held = ChronoUnit.DAYS.between(c.fact().date(), asOf.atZone(java.time.ZoneOffset.UTC).toLocalDate());
                if (held > config.transfers().holdWindowDays()) {
                    items.add(new ReviewItem(c.externalId(), ReviewItem.UNMATCHED_LEG,
                        c.fact().accountRef() + " " + c.fact().date(), Math.abs(c.fact().amount()),
                        c.fact().ingestedAt(), Hashes.sha256(ReviewItem.UNMATCHED_LEG + "|" + c.externalId())));
                }
            }

            // Effective DISMISS silences while no newer fact lands for the subject (§9.9.F). This
            // runs before the ineffective items are emitted, because it can add one of its own.
            Map<String, Long> dismissN = new HashMap<>();
            for (Decision d : effective) {
                if (d instanceof Decision.Dismiss dis) {
                    for (String raw : dis.externalIds()) {
                        // A BALANCE_BREAK's subject is an account ref, not a fact id; every other
                        // kind names a fact (or the decision n), resolved through supersession.
                        if (ReviewItem.BALANCE_BREAK.equals(dis.item())) {
                            if (!config.registry().accounts().containsKey(raw)) {
                                ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                                    "DISMISS names an unknown account " + raw));
                                continue;
                            }
                            dismissN.merge(dis.item() + "|" + raw, d.n(), Math::max);
                            continue;
                        }
                        // A SUSPECTED_RECURRING's subject is the grouping stem, not a fact id; a
                        // DORMANT_COMMITMENT/COMMITMENT_ARREARS subject is the commitment id.
                        if (ReviewItem.SUSPECTED_RECURRING.equals(dis.item())) {
                            if (!candidateKeys.contains(raw)) {
                                ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                                    "DISMISS names an unknown candidate " + raw));
                                continue;
                            }
                            dismissN.merge(dis.item() + "|" + raw, d.n(), Math::max);
                            continue;
                        }
                        if (ReviewItem.DORMANT_COMMITMENT.equals(dis.item())
                            || ReviewItem.COMMITMENT_ARREARS.equals(dis.item())) {
                            if (!commitmentIds.contains(raw)) {
                                ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                                    "DISMISS names an unknown commitment " + raw));
                                continue;
                            }
                            dismissN.merge(dis.item() + "|" + raw, d.n(), Math::max);
                            continue;
                        }
                        String id = resolve(raw);
                        if (id == null) {
                            ineffective.add(new IneffectiveDecision(d.n(), d.action().wire(),
                                "DISMISS names an unknown id " + raw));
                            continue;
                        }
                        String key = dis.item() + "|" + id;
                        dismissN.merge(key, d.n(), Math::max);
                    }
                }
            }

            // INEFFECTIVE_DECISION (never DISMISS-able, §9.8), emitted after DISMISS processing so
            // a DISMISS that named an unknown id is surfaced too.
            for (IneffectiveDecision bad : ineffective) {
                String subject = String.valueOf(bad.decisionN());
                items.add(new ReviewItem(subject, ReviewItem.INEFFECTIVE_DECISION,
                    bad.action() + ": " + bad.reason(), null, decisionAt(bad.decisionN()),
                    Hashes.sha256(ReviewItem.INEFFECTIVE_DECISION + "|" + subject + "|" + bad.reason())));
            }
            List<ReviewItem> kept = new ArrayList<>();
            for (ReviewItem item : items) {
                if (ReviewItem.INEFFECTIVE_DECISION.equals(item.kind())) {
                    kept.add(item);
                    continue;
                }
                Long dn = dismissN.get(item.kind() + "|" + item.subject());
                // A fact subject ages on its newest fact; a BALANCE_BREAK's account subject ages on
                // the newest fact of that account, so the item returns once the chain moves again.
                // A SUSPECTED_RECURRING ages on the candidate's facts (its stem group); a
                // commitment item ages on the facts the commitment matched.
                long newest;
                if (ReviewItem.SUSPECTED_RECURRING.equals(item.kind())) {
                    newest = newestFactByStem.getOrDefault(item.subject(), 0L);
                } else if (ReviewItem.DORMANT_COMMITMENT.equals(item.kind())
                    || ReviewItem.COMMITMENT_ARREARS.equals(item.kind())) {
                    newest = newestFactByCommitment.getOrDefault(item.subject(), 0L);
                } else {
                    newest = newestFactBySubject.getOrDefault(item.subject(),
                        newestFactByAccount.getOrDefault(item.subject(), 0L));
                }
                if (dn != null && dn > newest) {
                    continue; // silenced: the DISMISS post-dates the newest fact for the subject
                }
                kept.add(item);
            }
            kept.sort(Comparator.comparing(ReviewItem::kind).thenComparing(ReviewItem::subject));
            return kept;
        }

        /**
         * A {@code BALANCE_BREAK} per account whose transaction chain does not close (§6.9): a fork
         * with no explanation. The chain runs over {@code transaction} rows only; a {@code noop}
         * exclusion is not a break, it is named by the reconcile result. The subject is the account
         * ref — the item is dismissed by account, not by row.
         */
        private List<ReviewItem> balanceReview() {
            Set<String> declared = new TreeSet<>();
            for (Account a : config.registry().accounts().values()) {
                if (a.balanceSource() == BalanceSource.DECLARED) {
                    declared.add(a.ref());
                }
            }
            List<Fact> transactions = new ArrayList<>();
            List<Fact> noops = new ArrayList<>();
            for (CurrentFact c : current) {
                (c.role() == Role.NOOP ? noops : transactions).add(c.fact());
            }
            List<ReviewItem> out = new ArrayList<>();
            for (Reconciliation.AccountResult r : Reconciliation.reconcile(transactions, noops, declared).values()) {
                if (r.status() != Reconciliation.Status.BROKEN) {
                    continue;
                }
                String detail = "chain does not close (sum " + r.sum() + ")";
                out.add(new ReviewItem(r.accountRef(), ReviewItem.BALANCE_BREAK, detail, null,
                    newestIngested(r.accountRef()),
                    Hashes.sha256(ReviewItem.BALANCE_BREAK + "|" + r.accountRef() + "|" + detail)));
            }
            return out;
        }

        /** The newest fact ingest time on an account — a stable anchor for a derived item's age. */
        private Instant newestIngested(String accountRef) {
            Instant newest = Instant.EPOCH;
            for (CurrentFact c : current) {
                if (c.fact().accountRef().equals(accountRef) && c.fact().ingestedAt().isAfter(newest)) {
                    newest = c.fact().ingestedAt();
                }
            }
            return newest;
        }

        /** One review item per (subject, kind): subject the smallest id, a readable detail. */
        private void addGrouped(List<ReviewItem> items, Map<String, CurrentFact> byId, String subject,
                                String kind, List<String> members) {
            CurrentFact self = byId.get(subject);
            if (self == null) {
                return;
            }
            String detail = detail(kind, byId, members);
            items.add(new ReviewItem(subject, kind, detail, Math.abs(self.fact().amount()),
                self.fact().ingestedAt(), Hashes.sha256(kind + "|" + subject + "|" + detail)));
        }

        /**
         * A human detail rather than an id list: a restatement is interesting because the text
         * differs, a duplicate because the stem and amount repeat. The ids stay the subject and
         * in the log; the review queue is read by a person.
         */
        private static String detail(String kind, Map<String, CurrentFact> byId, List<String> members) {
            List<CurrentFact> facts = new ArrayList<>(members.size());
            for (String id : members) {
                CurrentFact c = byId.get(id);
                if (c != null) {
                    facts.add(c);
                }
            }
            if (facts.isEmpty()) {
                return String.join(",", members);
            }
            if (ReviewItem.RESTATEMENT.equals(kind)) {
                LinkedHashSet<String> texts = new LinkedHashSet<>();
                for (CurrentFact c : facts) {
                    texts.add(trex.v2.core.Clean.clean(c.fact().rawDescription()));
                }
                return clip(String.join("  /  ", texts));
            }
            CurrentFact first = facts.getFirst();
            return facts.size() + "\u00d7 " + MerchantStem.stem(first.fact().rawDescription())
                + " on " + first.fact().date();
        }

        private static String clip(String text) {
            return text.length() <= 140 ? text : text.substring(0, 139) + "\u2026";
        }

        /** A disjoint-set forest over list indices; the root of a component is its smallest index. */
        private static int[] components(int size) {
            int[] parent = new int[size];
            for (int i = 0; i < size; i++) {
                parent[i] = i;
            }
            return parent;
        }

        private static int find(int[] parent, int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];
                x = parent[x];
            }
            return x;
        }

        private static void union(int[] parent, int a, int b) {
            int ra = find(parent, a);
            int rb = find(parent, b);
            if (ra != rb) {
                parent[Math.max(ra, rb)] = Math.min(ra, rb);
            }
        }

        /** One item per component of at least two: subject the smallest id, detail every member. */
        private void addClusters(List<ReviewItem> items, Map<String, CurrentFact> byId,
                                 List<CurrentFact> list, int[] parent, String kind) {
            Map<Integer, TreeSet<String>> clusters = new TreeMap<>();
            for (int i = 0; i < list.size(); i++) {
                clusters.computeIfAbsent(find(parent, i), k -> new TreeSet<>()).add(list.get(i).externalId());
            }
            for (TreeSet<String> members : clusters.values()) {
                if (members.size() >= 2) {
                    addGrouped(items, byId, members.first(), kind, List.copyOf(members));
                }
            }
        }

        private Instant decisionAt(long n) {
            return decisions.stream().filter(d -> d.n() == n).map(Decision::at).findFirst()
                .orElse(Instant.EPOCH);
        }

        // ---- P12: units ---------------------------------------------------------------------

        /**
         * The counterpart of every clearing transfer (§6.10), materialised as a derived leg so a
         * clearing transfer has two concrete legs and the clearing account's ledger is queryable.
         * Never a fact: reserved {@code clr|…} id, no evidence, not a decision target. The balance is
         * derived — {@code opening + Σ movements}, landing on the account's declared closing.
         */
        private List<ClearingLeg> clearingLegs() {
            Map<String, CurrentFact> byId = currentById();
            Map<String, Long> movementSum = new HashMap<>();
            for (TransferRow t : transfers) {
                if (t.clearingAccount() == null) {
                    continue;
                }
                CurrentFact real = realLegOf(t, byId);
                if (real != null) {
                    movementSum.merge(t.clearingAccount(), -real.fact().amount(), Long::sum);
                }
            }
            Map<String, Long> running = new HashMap<>();
            for (Map.Entry<String, Long> e : movementSum.entrySet()) {
                Long closing = config.registry().findAccount(e.getKey()).map(a -> a.closingBalance()).orElse(0L);
                running.put(e.getKey(), (closing == null ? 0L : closing) - e.getValue());
            }
            List<TransferRow> ordered = new ArrayList<>();
            for (TransferRow t : transfers) {
                if (t.clearingAccount() != null && realLegOf(t, byId) != null) {
                    ordered.add(t);
                }
            }
            ordered.sort(Comparator.comparing((TransferRow t) -> realLegOf(t, byId).fact().date())
                .thenComparingLong(t -> realLegOf(t, byId).fact().n()));
            List<ClearingLeg> out = new ArrayList<>();
            for (TransferRow t : ordered) {
                Fact real = realLegOf(t, byId).fact();
                String account = t.clearingAccount();
                long amount = -real.amount();
                long balance = running.merge(account, amount, Long::sum);
                out.add(new ClearingLeg(t.transferId(), "clr|" + account + "|" + real.externalId(),
                    account, real.date(), amount, balance, "clearing \u00b7 " + real.rawDescription(),
                    real.externalId(), real.n()));
            }
            return out;
        }

        private CurrentFact realLegOf(TransferRow t, Map<String, CurrentFact> byId) {
            String realId = t.clearingAccount().equals(t.fromLeg()) ? t.toLeg() : t.fromLeg();
            return byId.get(realId);
        }

        private List<Unit> units() {
            List<Unit> out = new ArrayList<>();
            for (TransferRow t : transfers) {
                CurrentFact from = currentById().get(t.fromLeg());
                CurrentFact to = currentById().get(t.toLeg());
                if (t.clearingAccount() != null) {
                    // One real leg and an account side (§6.10): the unit is carried by the real leg.
                    CurrentFact real = from != null ? from : to;
                    if (real == null) {
                        continue;
                    }
                    out.add(new Unit(t.transferId(), Unit.KIND_TRANSFER, real.fact().accountRef(),
                        real.fact().date(), Math.abs(real.fact().amount()),
                        config.registry().account(real.fact().accountRef()).currency(),
                        RuleSet.TRANSFER, CategoryOrigin.STRUCTURAL, LegState.MATCHED, false, false));
                    continue;
                }
                if (from == null || to == null) {
                    continue;
                }
                out.add(new Unit(t.transferId(), Unit.KIND_TRANSFER, from.fact().accountRef(),
                    from.fact().date(), Math.abs(from.fact().amount()),
                    config.registry().account(from.fact().accountRef()).currency(),
                    RuleSet.TRANSFER, CategoryOrigin.STRUCTURAL, LegState.MATCHED, false, false));
            }
            for (CurrentFact c : current) {
                if (c.role() == Role.NOOP || c.leg() != LegState.EXTERNAL || attestation(c.fact())) {
                    continue;
                }
                out.add(new Unit(c.externalId(), Unit.KIND_EXTERNAL, c.fact().accountRef(), c.fact().date(),
                    c.fact().amount(), config.registry().account(c.fact().accountRef()).currency(),
                    c.category(), c.categoryOrigin(), c.leg(), false, false));
            }
            out.sort(Comparator.comparing(Unit::date).thenComparing(Unit::unitId));
            return out;
        }

        private boolean attestation(Fact f) {
            return config.registry().account(f.accountRef()).balanceSource() == BalanceSource.DECLARED
                && f.amount() == 0;
        }

        private Map<String, CurrentFact> currentById() {
            Map<String, CurrentFact> byId = new LinkedHashMap<>();
            for (CurrentFact c : current) {
                byId.put(c.externalId(), c);
            }
            return byId;
        }
    }

    /**
     * A declared commitment while the fold is built (V2-COMMITMENTS-PLAN.md §2.6): the latest
     * effective declaration's fields plus the latest effective retirement, if any. Not a derived
     * row — the cost face comes back from matching, so the matcher gets the declared amount as the
     * expectation and no observed steps.
     */
    private record DeclaredCommitment(String commitmentId, String name, String direction, Cadence cadence,
                                      AmountKind amountKind, CommitmentKind kind, List<Decision.Match> matches,
                                      Long amount, LocalDate anchor, String fromCandidate, long declaredN,
                                      Long retiredN, LocalDate endedAt) {

        DeclaredCommitment(Decision.DeclareCommitment dc, Long retiredN, LocalDate endedAt) {
            this(dc.commitmentId(), dc.name(), dc.direction(), dc.cadence(), dc.amountKind(),
                dc.commitmentKind(), dc.matches(), dc.amount(), dc.anchor(), dc.fromCandidate(),
                dc.n(), retiredN, endedAt);
        }

        boolean retired() {
            return retiredN != null;
        }

        DeclaredCommitment ended(long n, LocalDate at) {
            return new DeclaredCommitment(commitmentId, name, direction, cadence, amountKind, kind,
                matches, amount, anchor, fromCandidate, declaredN, n, at);
        }

        /** The declaration-shaped row the matcher needs: the declared amount is the expectation. */
        Commitment row() {
            Long signed = amount == null ? null
                : (Commitment.OUT.equals(direction) ? -Math.abs(amount) : Math.abs(amount));
            return new Commitment(commitmentId, null, name, CommitmentOrigin.DECLARED, direction,
                cadence, amountKind, kind, retired() ? CommitmentStatus.ENDED : CommitmentStatus.ACTIVE,
                null, null, anchor, signed, null, null, null, List.of(), null, null, 0, null,
                amountKind == AmountKind.VARIABLE, 0, null, declaredN, retiredN, endedAt);
        }
    }

    /** A dormant commitment's silence window: the last satisfied occurrence and the frontier. */
    private record Dormancy(LocalDate since, LocalDate frontier) {}

    static boolean oppositeSign(long a, long b) {
        return (a < 0 && b > 0) || (a > 0 && b < 0);
    }

    static Instant laterIngested(Fact a, Fact b) {
        return a.ingestedAt().isAfter(b.ingestedAt()) ? a.ingestedAt() : b.ingestedAt();
    }
}
