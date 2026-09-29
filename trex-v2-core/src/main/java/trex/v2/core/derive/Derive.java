package trex.v2.core.derive;

import trex.v2.core.Action;
import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.Hashes;
import trex.v2.core.Ids;
import trex.v2.core.MerchantStem;
import trex.v2.core.Observation;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Rule;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.RuleSubject;

import java.time.Instant;
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
 * facts, pairing, pending (P4 — empty for now), category, review items and units.
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
        Map<String, LegState> legState = new HashMap<>();
        Map<String, String> transferIdByLeg = new HashMap<>();
        List<TransferRow> transfers = List.of();
        List<ReviewItem> pairingReview = new ArrayList<>();
        List<ReviewItem> pendingReview = new ArrayList<>();
        List<PendingRow> pending = List.of();

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

            Map<String, CurrentFact> currentMap = currentFacts();
            pairing(new ArrayList<>(currentMap.values()));
            Map<String, CurrentFact> withPairing = applyPairing(currentMap);
            Map<String, CategoryRow> categories = categories(withPairing);
            current = withPairing.values().stream()
                .map(c -> withCategory(c, categories.get(c.externalId())))
                .sorted(Comparator.comparing((CurrentFact c) -> c.fact().date())
                    .thenComparingLong(c -> c.fact().n()))
                .toList();

            pending = pendingSettlement();
            List<ReviewItem> review = reviewItems();
            List<Unit> units = units();
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
                review,
                ineffective,
                units,
                userAcks());
        }

        /** The latest effective USER_ACK per (user, period) (V2-PROPOSAL.md §9.4). */
        private List<UserAckRow> userAcks() {
            Map<String, Decision.UserAck> latest = new TreeMap<>();
            for (Decision d : effective) {
                if (d instanceof Decision.UserAck ack) {
                    latest.put(ack.user() + '\u0000' + ack.period(), ack);
                }
            }
            return latest.values().stream()
                .map(a -> new UserAckRow(a.user(), a.period(), a.throughN(), a.stateHash(),
                    a.configRevision(), a.deriveVersion(), a.hashVersion(), a.at(), a.n()))
                .toList();
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
                    out.put(id, new CurrentFact(f, LegState.EXTERNAL, null, null, CategoryOrigin.NONE, null));
                }
            }
            return out;
        }

        // ---- P7: transfer shape and pairing -------------------------------------------------

        private void pairing(List<CurrentFact> currentFacts) {
            Map<String, CurrentFact> byId = new HashMap<>();
            for (CurrentFact c : currentFacts) {
                byId.put(c.externalId(), c);
            }
            Set<String> shapedReceipts = receiptsSharedAcrossAccounts(currentFacts);

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

            // Live decision pairs: a PAIR holds only while it is the latest decision for both legs.
            Map<String, TransferRow> rows = new TreeMap<>();
            Set<String> livePairLegs = new HashSet<>();
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
                        String tid = mintTransferId(fa, fb);
                        rows.put(tid, new TransferRow(tid, a, b, Confidence.MANUAL, "decision", p.n(),
                            p.at()));
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
                // A PAIR that is not live for both legs frees this leg back to the pool, so a leg
                // whose latest decision is a PAIR is still eligible; MARK_EXTERNAL/UNPAIR are not.
                if (!isShaped(c.fact(), shapedReceipts)) {
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
                if (!isShaped(f.fact(), shapedReceipts)) {
                    legState.put(f.externalId(), LegState.EXTERNAL);
                    continue;
                }
                // T1 — receipt.
                List<Integer> t1 = candidates(eligible, paired, i, (g) ->
                    f.fact().receipt() != null && !f.fact().receipt().isBlank()
                        && f.fact().receipt().equals(g.fact().receipt())
                        && !g.fact().accountRef().equals(f.fact().accountRef())
                        && oppositeSign(f.fact().amount(), g.fact().amount())
                        && currency(g).equals(currency(f)));
                if (t1.size() == 1) {
                    pairT1(rows, paired, eligible, i, t1.getFirst(), f);
                    continue;
                }
                if (t1.size() > 1) {
                    legState.put(f.externalId(), LegState.HELD);
                    pairingReview.add(ambiguous(f, t1, eligible));
                    continue;
                }
                // T2 — same day, equal stem.
                List<Integer> t2 = candidates(eligible, paired, i, (g) ->
                    Math.abs(g.fact().amount()) == Math.abs(f.fact().amount())
                        && oppositeSign(f.fact().amount(), g.fact().amount())
                        && !g.fact().accountRef().equals(f.fact().accountRef())
                        && currency(g).equals(currency(f))
                        && g.fact().date().equals(f.fact().date())
                        && MerchantStem.transferStem(g.fact().rawDescription())
                            .equals(MerchantStem.transferStem(f.fact().rawDescription())));
                if (t2.size() == 1) {
                    pairT2(rows, paired, eligible, i, t2.getFirst(), f, Confidence.HIGH);
                    continue;
                }
                if (t2.size() > 1) {
                    legState.put(f.externalId(), LegState.HELD);
                    pairingReview.add(ambiguous(f, t2, eligible));
                    continue;
                }
                // T3 — windowed.
                int window = config.transfers().windowDays();
                List<Integer> t3 = candidates(eligible, paired, i, (g) ->
                    Math.abs(g.fact().amount()) == Math.abs(f.fact().amount())
                        && oppositeSign(f.fact().amount(), g.fact().amount())
                        && !g.fact().accountRef().equals(f.fact().accountRef())
                        && currency(g).equals(currency(f))
                        && !g.fact().date().equals(f.fact().date())
                        && Math.abs(ChronoUnit.DAYS.between(f.fact().date(), g.fact().date())) <= window
                        && MerchantStem.transferStem(g.fact().rawDescription())
                            .equals(MerchantStem.transferStem(f.fact().rawDescription())));
                if (t3.size() == 1) {
                    pairT2(rows, paired, eligible, i, t3.getFirst(), f, Confidence.HIGH);
                    continue;
                }
                if (t3.size() > 1) {
                    legState.put(f.externalId(), LegState.HELD);
                    pairingReview.add(ambiguous(f, t3, eligible));
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
                                         java.util.function.Predicate<CurrentFact> filter) {
            List<Integer> out = new ArrayList<>();
            for (int j = 0; j < eligible.size(); j++) {
                if (j == self || paired[j]) {
                    continue;
                }
                if (filter.test(eligible.get(j))) {
                    out.add(j);
                }
            }
            return out;
        }

        private void pairT1(Map<String, TransferRow> rows, boolean[] paired, List<CurrentFact> eligible,
                            int i, int j, CurrentFact f) {
            CurrentFact g = eligible.get(j);
            String tid = Ids.transferId(f.fact().receipt());
            rows.put(tid, new TransferRow(tid, f.externalId(), g.externalId(), Confidence.EXACT, "derived",
                null, laterIngested(f.fact(), g.fact())));
            paired[i] = true;
            paired[j] = true;
            legState.put(f.externalId(), LegState.MATCHED);
            legState.put(g.externalId(), LegState.MATCHED);
        }

        private void pairT2(Map<String, TransferRow> rows, boolean[] paired, List<CurrentFact> eligible,
                            int i, int j, CurrentFact f, Confidence confidence) {
            CurrentFact g = eligible.get(j);
            String tid = mintTransferId(f.fact(), g.fact());
            rows.put(tid, new TransferRow(tid, f.externalId(), g.externalId(), confidence, "derived",
                null, laterIngested(f.fact(), g.fact())));
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

        private String currency(CurrentFact c) {
            Account account = config.registry().account(c.fact().accountRef());
            return account.currency();
        }

        private Set<String> receiptsSharedAcrossAccounts(List<CurrentFact> currentFacts) {
            Map<String, Set<String>> receipts = new TreeMap<>();
            for (CurrentFact c : currentFacts) {
                if (c.fact().receipt() != null && !c.fact().receipt().isBlank()) {
                    receipts.computeIfAbsent(c.fact().receipt(), k -> new TreeSet<>()).add(c.fact().accountRef());
                }
            }
            Set<String> shared = new HashSet<>();
            receipts.forEach((receipt, accounts) -> {
                if (accounts.size() > 1) {
                    shared.add(receipt);
                }
            });
            return shared;
        }

        private boolean isShaped(Fact f, Set<String> shapedReceipts) {
            if (config.transfers().isTransferShaped(f.rawDescription())) {
                return true;
            }
            return f.receipt() != null && shapedReceipts.contains(f.receipt());
        }

        private Map<String, CurrentFact> applyPairing(Map<String, CurrentFact> currentMap) {
            Map<String, CurrentFact> out = new LinkedHashMap<>();
            currentMap.forEach((id, c) -> out.put(id, new CurrentFact(c.fact(),
                legState.getOrDefault(id, LegState.EXTERNAL), transferIdByLeg.get(id), null,
                CategoryOrigin.NONE, null)));
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

        private CurrentFact withCategory(CurrentFact c, CategoryRow row) {
            if (row == null) {
                return c;
            }
            return new CurrentFact(c.fact(), c.leg(), c.transferId(), row.category(), row.origin(),
                row.ruleId());
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

        // ---- P10: review items --------------------------------------------------------------

        private List<ReviewItem> reviewItems() {
            List<ReviewItem> items = new ArrayList<>(pairingReview);
            items.addAll(pendingReview);
            Map<String, Long> newestFactBySubject = new TreeMap<>();
            for (Fact f : facts) {
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
            for (int i = 0; i < currentList.size(); i++) {
                for (int j = i + 1; j < currentList.size(); j++) {
                    CurrentFact a = currentList.get(i);
                    CurrentFact b = currentList.get(j);
                    if (!a.fact().accountRef().equals(b.fact().accountRef())
                        || !a.fact().date().equals(b.fact().date())
                        || a.matched() || b.matched()) {
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
                    boolean similar = MerchantStem.similar(a.fact().rawDescription(), b.fact().rawDescription(),
                        config.transfers().restatementOverlap());
                    if (sameAmount && similar) {
                        union(restParent, i, j);
                    }
                }
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
                long newest = newestFactBySubject.getOrDefault(item.subject(), 0L);
                if (dn != null && dn > newest) {
                    continue; // silenced: the DISMISS post-dates the newest fact for the subject
                }
                kept.add(item);
            }
            kept.sort(Comparator.comparing(ReviewItem::kind).thenComparing(ReviewItem::subject));
            return kept;
        }

        /** One review item per (subject, kind), listing every member id; the table keys on both. */
        private void addGrouped(List<ReviewItem> items, Map<String, CurrentFact> byId, String subject,
                                String kind, Set<String> members) {
            CurrentFact self = byId.get(subject);
            if (self == null) {
                return;
            }
            String detail = String.join(",", members);
            items.add(new ReviewItem(subject, kind, detail, Math.abs(self.fact().amount()),
                self.fact().ingestedAt(), Hashes.sha256(kind + "|" + subject + "|" + detail)));
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
                    addGrouped(items, byId, members.first(), kind, members);
                }
            }
        }

        private Instant decisionAt(long n) {
            return decisions.stream().filter(d -> d.n() == n).map(Decision::at).findFirst()
                .orElse(Instant.EPOCH);
        }

        // ---- P11: units ---------------------------------------------------------------------

        private List<Unit> units() {
            List<Unit> out = new ArrayList<>();
            for (TransferRow t : transfers) {
                CurrentFact from = currentById().get(t.fromLeg());
                CurrentFact to = currentById().get(t.toLeg());
                if (from == null || to == null) {
                    continue;
                }
                out.add(new Unit(t.transferId(), Unit.KIND_TRANSFER, from.fact().accountRef(),
                    from.fact().date(), Math.abs(from.fact().amount()),
                    config.registry().account(from.fact().accountRef()).currency(),
                    RuleSet.TRANSFER, CategoryOrigin.STRUCTURAL, LegState.MATCHED, false, false));
            }
            for (CurrentFact c : current) {
                if (c.leg() != LegState.EXTERNAL || attestation(c.fact())) {
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

    static boolean oppositeSign(long a, long b) {
        return (a < 0 && b > 0) || (a > 0 && b < 0);
    }

    static Instant laterIngested(Fact a, Fact b) {
        return a.ingestedAt().isAfter(b.ingestedAt()) ? a.ingestedAt() : b.ingestedAt();
    }
}
