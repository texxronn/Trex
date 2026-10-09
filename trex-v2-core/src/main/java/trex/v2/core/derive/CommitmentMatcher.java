package trex.v2.core.derive;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The pure occurrence matcher (V2-COMMITMENTS-PLAN.md §2.5, §2.9; Stage 2). It takes plain,
 * resolved inputs — declared commitments, their effective {@link CommitmentRule}s, the current
 * facts, resolved {@link CommitmentPin}s and {@link CommitmentSettle}s and an explicit
 * {@code asOf} — and materialises the occurrences, assigns each fact to at most one commitment,
 * and applies pins and settles. Decisions and the supersession fold are Stage 3/4; nothing here
 * reads a journal decision.
 *
 * <p>Regular schedules are generated with {@code java.time} calendar arithmetic from the anchor
 * (never "add 30 days"), so a monthly bill on the 31st clamps in February and returns to the 31st
 * afterwards. Dates run from {@code max(anchor, asOf − 12 months)} through {@code asOf + 92 days},
 * each with a window of {@code ± min(cadence/2, 7)} days. An {@code irregular} commitment
 * generates no dates: every matching fact is recorded at its own date, with no window, no missed
 * and no arrears.
 *
 * <p>Assignment is global and deterministic: facts are processed in {@code (date, n)} order, pins
 * first, then the rules, where a fact matching several commitments goes to the latest declaration
 * ({@code declaredN}, ties by commitment id). Facts of the wrong sign never match. Every assigned
 * fact then <b>attaches</b> to the occurrence whose window contains its date
 * (V2-MANUAL-ARREARS-PLAN.md §3.1): several facts in one window sum, and the occurrence is
 * {@code occurred} at what actually moved — the declared or stepped expectation is a forecast for
 * windows with no fact, never a shortfall. Nothing is allocated across occurrences and nothing
 * pre-pays; a fact with no window is an {@code off_schedule} occurrence at its own date, and a
 * same-day extra merges into the row already there (§2.5) — the table keys one row per
 * {@code (commitment, dueDate)}, so no second row and no dropped amount. Overlapping windows
 * (fortnightly ± 7 meets on the boundary day) resolve to the earliest due date, so the answer
 * cannot depend on list order. The claim is unbounded: a regular commitment's facts that predate
 * its first materialised window are still bound to it in the returned {@link CommitmentFact} map
 * (V2-COMMITMENT-FACT-PLAN.md §3.1) — the span gates display, not association — but they are never
 * folded onto the oldest occurrence. A retired commitment ({@code endedAt} set) stops generating
 * occurrences after it ended (an occurrence on {@code endedAt} still counts) and takes no fact
 * dated after it; its historical occurrences and arrears remain visible. The output is ordered by
 * commitment id then due date, and no iteration depends on input order.
 */
final class CommitmentMatcher {

    /** How a fact was placed onto an occurrence (§2.7). */
    private static final String BY_RULE = "rule";
    private static final String BY_PIN = "pin";

    /** The materialised past: twelve months of occurrences (§2.5, §10.2). */
    private static final int PAST_MONTHS = 12;

    /** The materialised future: {@code asOf + 92 days} (§2.5, §10.2). */
    private static final int HORIZON_DAYS = 92;

    /**
     * The matching window: {@code ± min(cadence/2, 7)} days (§2.5, provisional). The cap absorbs
     * weekends and month lengths; for a weekly cadence the half-period is 3 days, for everything
     * fortnightly and longer it is the 7-day cap — fortnightly windows then touch on the boundary
     * day, and attachment resolves it to the earliest due date (V2-MANUAL-ARREARS-PLAN.md §3.1).
     */
    private static final int WINDOW_MAX_DAYS = 7;

    private static final Comparator<CurrentFact> BY_DATE_N = Comparator
        .comparing((CurrentFact c) -> c.fact().date())
        .thenComparingLong(c -> c.fact().n());

    private CommitmentMatcher() {}

    static CommitmentMatch match(List<Commitment> commitments, List<CommitmentRule> rules,
                                 List<CurrentFact> current, List<CommitmentPin> pins,
                                 List<CommitmentSettle> settles,
                                 List<CommitmentExclusion> exclusions, Instant asOf) {
        LocalDate asOfDate = asOf.atZone(ZoneOffset.UTC).toLocalDate();

        // Only declarations expect occurrences; a detected candidate is a discovery, not a
        // schedule (§2.1, §2.5). Sorted by id so the output order cannot follow the caller's list.
        List<Commitment> tracked = new ArrayList<>();
        for (Commitment commitment : commitments) {
            if (commitment.origin() == CommitmentOrigin.DECLARED) {
                tracked.add(commitment);
            }
        }
        tracked.sort(Comparator.comparing(Commitment::commitmentId));
        Map<String, Commitment> committedById = new TreeMap<>();
        for (Commitment commitment : tracked) {
            committedById.put(commitment.commitmentId(), commitment);
        }

        // Rule assignment order: the latest declaration wins, then the id for determinism (§2.2).
        // A null declaredN counts as the oldest — a hand-built row has no decision behind it.
        List<Commitment> byDeclaration = new ArrayList<>(tracked);
        byDeclaration.sort(Comparator
            .comparingLong((Commitment c) -> c.declaredN() == null ? Long.MIN_VALUE : c.declaredN())
            .reversed()
            .thenComparing(Commitment::commitmentId));

        // The fact domain (§2.2): every current fact except noop; a fact after asOf is not yet
        // observed at asOf and cannot be matched. Sorted so (date, n) processing is explicit.
        List<CurrentFact> facts = new ArrayList<>();
        for (CurrentFact candidate : current) {
            if (candidate.role() == Role.NOOP || candidate.fact().date().isAfter(asOfDate)) {
                continue;
            }
            facts.add(candidate);
        }
        facts.sort(BY_DATE_N);

        // Pins: one fact to one commitment. The pairs are sorted before folding, so a duplicate
        // (which the Stage 3 resolution prevents) cannot make the answer depend on list order.
        Map<String, String> pinByFact = new TreeMap<>();
        List<CommitmentPin> orderedPins = new ArrayList<>(pins);
        orderedPins.sort(Comparator.comparing(CommitmentPin::externalId)
            .thenComparing(CommitmentPin::commitmentId));
        for (CommitmentPin pin : orderedPins) {
            pinByFact.putIfAbsent(pin.externalId(), pin.commitmentId());
        }

        // Excluded (commitment, fact) pairs (V2-COMMITMENT-EXCLUSIONS-PLAN.md §4): the person's
        // conclusion that a rule-matched movement is a one-off. The pair is never claimed — a pin
        // falls through to the rules and a rule scan skips it — so it shapes neither occurrences
        // nor cost, while the fact itself stays untouched.
        Set<String> excludedPairs = new TreeSet<>();
        for (CommitmentExclusion exclusion : exclusions) {
            excludedPairs.add(exclusion.commitmentId() + '\u0000' + exclusion.externalId());
        }

        // The effective settles, latest decision per occurrence wins; a settle naming an
        // occurrence that is not materialised is ignored here (the derived table only holds
        // materialised dates).
        Map<String, Map<LocalDate, Long>> settleByCommitment = new TreeMap<>();
        for (CommitmentSettle settle : settles) {
            if (!committedById.containsKey(settle.commitmentId())) {
                continue;
            }
            settleByCommitment
                .computeIfAbsent(settle.commitmentId(), k -> new TreeMap<>())
                .merge(settle.dueDate(), settle.decisionN(), Math::max);
        }

        // Compile each tracked commitment's rules once, with the categories.yaml convention: a
        // case-insensitive find over Clean.clean(rawDescription), optional account scope (§2.2).
        Map<String, List<CommitmentRules.CompiledRule>> rulesByCommitment = new TreeMap<>();
        for (CommitmentRule rule : rules) {
            if (!committedById.containsKey(rule.commitmentId())) {
                continue;
            }
            rulesByCommitment.computeIfAbsent(rule.commitmentId(), k -> new ArrayList<>())
                .add(CommitmentRules.compile(rule));
        }

        // Materialise every regular schedule before assignment: its first window starts the span
        // of occurrences a fact may land on, so a fact before it is history the occurrence set
        // does not hold — never folded onto the oldest materialised occurrence. An irregular
        // commitment has no calendar to bound against.
        Map<String, List<Slot>> slotsByCommitment = new TreeMap<>();
        Map<String, LocalDate> spanStart = new TreeMap<>();
        for (Commitment commitment : tracked) {
            List<Slot> slots = new ArrayList<>();
            if (commitment.cadence() != Cadence.IRREGULAR) {
                materialise(commitment, asOfDate, slots);
            }
            slotsByCommitment.put(commitment.commitmentId(), slots);
            if (!slots.isEmpty()) {
                spanStart.put(commitment.commitmentId(), slots.getFirst().windowStart);
            }
        }

        // Claim each fact for at most one commitment: the pin first, then the latest declaration
        // whose rules, sign and life admit it. The claim is unbounded — the materialised window
        // decides only what is displayed as an occurrence (§3.2), so every claimed fact is bound.
        Map<String, List<Assigned>> assignedByCommitment = new TreeMap<>();
        for (CurrentFact fact : facts) {
            String pinnedTo = pinByFact.get(fact.externalId());
            if (pinnedTo != null) {
                Commitment pinned = committedById.get(pinnedTo);
                if (pinned != null && signMatches(pinned, fact)
                    && !afterLife(pinned, fact)
                    && !excludedPairs.contains(pinnedTo + '\u0000' + fact.externalId())) {
                    assignedByCommitment.computeIfAbsent(pinnedTo, k -> new ArrayList<>())
                        .add(new Assigned(fact, BY_PIN));
                    continue;
                }
            }
            for (Commitment candidate : byDeclaration) {
                if (!signMatches(candidate, fact)
                    || afterLife(candidate, fact)
                    || excludedPairs.contains(candidate.commitmentId() + '\u0000'
                        + fact.externalId())) {
                    continue;
                }
                List<CommitmentRules.CompiledRule> candidateRules = rulesByCommitment.get(candidate.commitmentId());
                if (candidateRules == null || !anyMatch(candidateRules, fact)) {
                    continue;
                }
                assignedByCommitment.computeIfAbsent(candidate.commitmentId(), k -> new ArrayList<>())
                    .add(new Assigned(fact, BY_RULE));
                break;
            }
        }

        List<CommitmentOccurrence> occurrences = new ArrayList<>();
        List<CommitmentArrears> arrears = new ArrayList<>();
        for (Commitment commitment : tracked) {
            List<Assigned> assigned =
                assignedByCommitment.getOrDefault(commitment.commitmentId(), List.of());
            List<Slot> slots = slotsByCommitment.get(commitment.commitmentId());
            if (commitment.cadence() == Cadence.IRREGULAR) {
                irregular(assigned, slots);
            } else {
                // The claim is unbounded; the materialised window decides only what is displayed.
                // A fact with no materialised window to attach to — before the first window, or a
                // schedule with no window at all (an old retirement) — is bound (in the map) but
                // attaches to nothing: never folded onto an occurrence and never off-schedule
                // (§3.2).
                LocalDate span = spanStart.get(commitment.commitmentId());
                applySettles(commitment, settleByCommitment.get(commitment.commitmentId()), slots);
                attach(assigned.stream()
                    .filter(a -> span != null && !beforeSpan(span, a.fact())).toList(), slots);
                slots.sort(Comparator.comparing((Slot s) -> s.dueDate)
                    .thenComparing(s -> s.offSchedule));
            }
            for (Slot slot : slots) {
                occurrences.add(slot.toOccurrence(commitment.commitmentId()));
            }
            arrears.add(arrears(commitment, slots, asOfDate));
        }

        // The reverse map: every claimed fact, in fact-id order so the result never depends on the
        // caller's list order. A claimed fact is bound whether or not it landed on an occurrence.
        List<CommitmentFact> bindings = new ArrayList<>();
        for (Map.Entry<String, List<Assigned>> e : assignedByCommitment.entrySet()) {
            for (Assigned a : e.getValue()) {
                bindings.add(new CommitmentFact(a.fact().externalId(), e.getKey(), a.matchedBy()));
            }
        }
        bindings.sort(Comparator.comparing(CommitmentFact::externalId));
        return new CommitmentMatch(occurrences, arrears, bindings);
    }

    /**
     * True when the fact predates a regular schedule's first materialised window: it is history
     * outside the occurrence set and must not satisfy an occurrence. It gates <b>attachment</b>
     * only — the claim is unbounded, so such a fact is still bound to the commitment in the map
     * (§3.1). A null bound — no materialised occurrence, or an irregular commitment — excludes
     * nothing.
     */
    private static boolean beforeSpan(LocalDate spanStart, CurrentFact fact) {
        return spanStart != null && fact.fact().date().isBefore(spanStart);
    }

    /**
     * True when a retired commitment does not take this fact: a fact dated after {@code endedAt}
     * is outside the commitment's life. Life is purely the date bound — the span no longer plays a
     * part, so an old retirement still binds its historical facts. An ending on {@code endedAt}
     * itself still counts (§6.11).
     */
    private static boolean afterLife(Commitment commitment, CurrentFact fact) {
        return commitment.endedAt() != null && fact.fact().date().isAfter(commitment.endedAt());
    }

    // ---- generation ---------------------------------------------------------------------------

    /**
     * Materialise the regular occurrences of {@code commitment} (§2.5): dates are
     * {@code anchor + k periods}, computed from the anchor every time, so a clamped month returns
     * to the anchor's day of month and never drifts; each date carries its {@code ± window} and is
     * born {@code missed} once its window has closed at {@code asOf}.
     */
    private static void materialise(Commitment commitment, LocalDate asOfDate, List<Slot> slots) {
        LocalDate anchor = commitment.anchorDate();
        if (anchor == null) {
            throw new IllegalArgumentException("regular commitment '" + commitment.commitmentId()
                + "' has no anchor date to generate occurrences from");
        }
        LocalDate past = asOfDate.minusMonths(PAST_MONTHS);
        LocalDate from = anchor.isAfter(past) ? anchor : past;
        LocalDate horizon = asOfDate.plusDays(HORIZON_DAYS);
        // A retired commitment stops at endedAt: no occurrence is due after it, but the one on
        // endedAt itself still counts (§2.6, §6.11).
        LocalDate end = commitment.endedAt();
        int offset = Math.min(commitment.cadence().days() / 2, WINDOW_MAX_DAYS);

        long k = 0;
        LocalDate due = anchor;
        while (due.isBefore(from)) {
            k++;
            due = addPeriods(anchor, commitment.cadence(), k);
        }
        while (!due.isAfter(horizon) && (end == null || !due.isAfter(end))) {
            LocalDate windowEnd = due.plusDays(offset);
            slots.add(new Slot(due, due.minusDays(offset), windowEnd,
                windowEnd.isBefore(asOfDate) ? OccurrenceStatus.MISSED : OccurrenceStatus.DUE));
            k++;
            due = addPeriods(anchor, commitment.cadence(), k);
        }
    }

    /** The calendar period of a cadence; {@code irregular} has none. */
    private static LocalDate addPeriods(LocalDate anchor, Cadence cadence, long k) {
        return switch (cadence) {
            case WEEKLY -> anchor.plusWeeks(k);
            case FORTNIGHTLY -> anchor.plusWeeks(2 * k);
            case MONTHLY -> anchor.plusMonths(k);
            case BIMONTHLY -> anchor.plusMonths(2 * k);
            case QUARTERLY -> anchor.plusMonths(3 * k);
            case SEMIANNUAL -> anchor.plusMonths(6 * k);
            case ANNUAL -> anchor.plusYears(k);
            case IRREGULAR -> throw new IllegalArgumentException("irregular generates no dates");
        };
    }

    // ---- pins, rules and allocation -----------------------------------------------------------

    /**
     * Apply the effective settles before any matching: a settled occurrence is not open for
     * allocation and a later fact skips it (§2.9). The amount is the expected price — a person
     * concluded the expectation was met, so the row states the expectation, not a fabricated fact.
     */
    private static void applySettles(Commitment commitment, Map<LocalDate, Long> settled,
                                     List<Slot> slots) {
        if (settled == null) {
            return;
        }
        for (Slot slot : slots) {
            Long decisionN = settled.get(slot.dueDate);
            if (decisionN == null) {
                continue;
            }
            slot.status = OccurrenceStatus.SETTLED;
            slot.settleN = decisionN;
            slot.amount = expected(commitment, slot.dueDate);
        }
    }

    /**
     * Attach each assigned fact to the occurrence its date lands on (V2-MANUAL-ARREARS-PLAN.md
     * §3.1). A window's facts sum and the occurrence is {@code occurred} at what actually moved;
     * the declared or stepped expectation is a forecast for windows with no fact, never a
     * shortfall. Overlapping windows resolve to the earliest due date, so the answer cannot depend
     * on list order. A fact with no window is an {@code off_schedule} occurrence at its own date —
     * nothing is allocated across occurrences and nothing is swallowed.
     */
    private static void attach(List<Assigned> facts, List<Slot> slots) {
        for (Assigned assigned : facts) {
            CurrentFact fact = assigned.fact;
            LocalDate date = fact.fact().date();
            Slot window = windowFor(slots, date);
            if (window == null) {
                offSchedule(assigned, slots, Math.abs(fact.fact().amount()));
                continue;
            }
            // The first fact on the row names it; later facts in the same window only sum.
            if (window.matchedExternalId == null) {
                window.matchedExternalId = fact.externalId();
                window.matchedDate = date;
                window.matchedBy = assigned.matchedBy;
            }
            window.amount = (window.amount == null ? 0L : window.amount) + fact.fact().amount();
            window.status = OccurrenceStatus.OCCURRED;
        }
    }

    /** The scheduled occurrence whose window contains the date, earliest due date first. */
    private static Slot windowFor(List<Slot> slots, LocalDate date) {
        for (Slot slot : slots) {
            if (!slot.offSchedule && slot.windowStart != null && slot.windowEnd != null
                && !date.isBefore(slot.windowStart) && !date.isAfter(slot.windowEnd)) {
                return slot;
            }
        }
        return null;
    }

    /**
     * Record a fact that has no window to land on at its own date; the occurrence table's natural
     * overflow, never a silent adjustment. The table keys one row per {@code (commitment,
     * dueDate)}, so detection's same-day collapse (§2.3.3) applies on the outcome side too (§2.5):
     * an amount whose date already has a row merges into it — the day's total is preserved and no
     * money is dropped. An off-schedule row keeps the earliest fact, because facts are processed in
     * {@code (date, n)} order.
     */
    private static void offSchedule(Assigned assigned, List<Slot> slots, long amount) {
        CurrentFact fact = assigned.fact;
        LocalDate date = fact.fact().date();
        long signed = fact.fact().amount() < 0 ? -amount : amount;
        for (Slot slot : slots) {
            if (slot.dueDate.equals(date)) {
                slot.amount = (slot.amount == null ? 0L : slot.amount) + signed;
                return;
            }
        }
        Slot slot = new Slot(date, null, null, OccurrenceStatus.OCCURRED);
        slot.offSchedule = true;
        slot.amount = signed;
        slot.matchedExternalId = fact.externalId();
        slot.matchedDate = date;
        slot.matchedBy = assigned.matchedBy;
        slots.add(slot);
    }

    /**
     * An irregular commitment predicts nothing: every matching fact is an occurrence at its own
     * date, with no window and no arrears (§2.5, §10.2). Same-day repeats collapse into one row —
     * the occurrence table keys by due date, and the net movement is what happened that day.
     */
    private static void irregular(List<Assigned> facts, List<Slot> slots) {
        for (Assigned assigned : facts) {
            CurrentFact fact = assigned.fact;
            Slot existing = null;
            for (Slot slot : slots) {
                if (slot.dueDate.equals(fact.fact().date())) {
                    existing = slot;
                    break;
                }
            }
            if (existing == null) {
                Slot slot = new Slot(fact.fact().date(), null, null, OccurrenceStatus.OCCURRED);
                slot.amount = fact.fact().amount();
                slot.matchedExternalId = fact.externalId();
                slot.matchedDate = fact.fact().date();
                slot.matchedBy = assigned.matchedBy;
                slots.add(slot);
            } else {
                existing.amount += fact.fact().amount();
            }
        }
    }

    // ---- arrears ------------------------------------------------------------------------------

    /**
     * The commitment's backlog (V2-MANUAL-ARREARS-PLAN.md §3.4): every closed-window occurrence
     * with no fact — a hole — is counted, and {@code lapsed} mirrors the most recent closed-window
     * occurrence, the current red state, never the older backlog.
     */
    private static CommitmentArrears arrears(Commitment commitment, List<Slot> slots,
                                             LocalDate asOfDate) {
        int count = 0;
        long amount = 0;
        Slot recent = null;
        for (Slot slot : slots) {
            if (slot.offSchedule || slot.windowEnd == null) {
                continue;
            }
            if (slot.status == OccurrenceStatus.MISSED) {
                count++;
                Long expected = expected(commitment, slot.dueDate);
                amount += expected == null ? 0 : expected;
            }
            if (slot.windowEnd.isBefore(asOfDate)
                && (recent == null || slot.dueDate.isAfter(recent.dueDate))) {
                recent = slot;
            }
        }
        boolean lapsed = recent != null && recent.status == OccurrenceStatus.MISSED;
        return new CommitmentArrears(commitment.commitmentId(), count, amount, lapsed);
    }

    /**
     * The price point at or before the occurrence's due date (§2.5): the amount the period is
     * expected to cost. Before the first point the first point's amount; with no timeline at all,
     * the declared current amount. The step timeline records price changes as they happen, so an
     * old occurrence keeps the price it was expected at.
     */
    private static Long expected(Commitment commitment, LocalDate dueDate) {
        List<Commitment.PriceStep> steps = commitment.steps();
        if (steps.isEmpty()) {
            return commitment.currentAmount();
        }
        Commitment.PriceStep at = null;
        Commitment.PriceStep first = null;
        for (Commitment.PriceStep step : steps) {
            if (first == null || step.date().isBefore(first.date())) {
                first = step;
            }
            if (!step.date().isAfter(dueDate)
                && (at == null || step.date().isAfter(at.date()))) {
                at = step;
            }
        }
        return at == null ? first.amount() : at.amount();
    }

    // ---- small helpers ------------------------------------------------------------------------

    private static boolean anyMatch(List<CommitmentRules.CompiledRule> rules, CurrentFact fact) {
        for (CommitmentRules.CompiledRule rule : rules) {
            if (rule.matches(fact)) {
                return true;
            }
        }
        return false;
    }

    /** Only facts of the commitment's sign can match (§2.2). */
    private static boolean signMatches(Commitment commitment, CurrentFact fact) {
        long amount = fact.fact().amount();
        return amount != 0 && commitment.outgoing() == (amount < 0);
    }

    /** One assigned fact; {@code matchedBy} is {@code pin} or {@code rule}. */
    private record Assigned(CurrentFact fact, String matchedBy) {}

    /**
     * One occurrence while it is being built. {@code amount} is the summed movement of the facts
     * attached to the window (or the expected amount on a settled row), and the window is null for
     * irregular and off-schedule rows.
     */
    private static final class Slot {
        final LocalDate dueDate;
        final LocalDate windowStart;
        final LocalDate windowEnd;
        OccurrenceStatus status;
        String matchedExternalId;
        LocalDate matchedDate;
        String matchedBy;
        boolean offSchedule;
        Long settleN;
        Long amount;

        Slot(LocalDate dueDate, LocalDate windowStart, LocalDate windowEnd, OccurrenceStatus status) {
            this.dueDate = dueDate;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.status = status;
        }

        CommitmentOccurrence toOccurrence(String commitmentId) {
            return new CommitmentOccurrence(commitmentId, dueDate, status, windowStart, windowEnd,
                matchedExternalId, matchedDate, matchedBy, offSchedule, settleN, amount, null)
                .hashed();
        }
    }
}
