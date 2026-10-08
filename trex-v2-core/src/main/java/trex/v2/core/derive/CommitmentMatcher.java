package trex.v2.core.derive;

import trex.v2.core.Clean;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
 * ({@code declaredN}, ties by commitment id). Facts of the wrong sign never match. A rule fact is
 * allocated oldest-first across the commitment's open occurrences — the arrears clear from the
 * front and a surplus pre-pays the materialised future; a pin satisfies the nearest open
 * occurrence within one full cadence; anything left over is an {@code off_schedule} occurrence at
 * the fact's date. The output is ordered by commitment id then due date, and no iteration depends
 * on input order.
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
     * weekends and month lengths without letting neighbouring periods overlap; for a weekly
     * cadence the half-period is 3 days, for everything fortnightly and longer it is the 7-day cap.
     */
    private static final int WINDOW_MAX_DAYS = 7;

    private static final Comparator<CurrentFact> BY_DATE_N = Comparator
        .comparing((CurrentFact c) -> c.fact().date())
        .thenComparingLong(c -> c.fact().n());

    /**
     * A fact covers an occurrence "fully" — within {@code max(2%, 50¢)} of its expected amount
     * (§2.9). The percentage absorbs FX and rounding wobble on a real price; the cents floor
     * keeps a small bill from going {@code partial} over a sub-dollar difference. Below the band
     * the occurrence is {@code partial} and only the remainder stays in arrears.
     */
    private static final double FULL_FRACTION = 0.02;

    /** The cents floor of the full-coverage tolerance: half a dollar. */
    private static final long FULL_MIN_CENTS = 50;

    private CommitmentMatcher() {}

    static CommitmentMatch match(List<Commitment> commitments, List<CommitmentRule> rules,
                                 List<CurrentFact> current, List<CommitmentPin> pins,
                                 List<CommitmentSettle> settles, Instant asOf) {
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
        Map<String, List<CompiledRule>> rulesByCommitment = new TreeMap<>();
        for (CommitmentRule rule : rules) {
            if (!committedById.containsKey(rule.commitmentId())) {
                continue;
            }
            rulesByCommitment.computeIfAbsent(rule.commitmentId(), k -> new ArrayList<>())
                .add(new CompiledRule(rule, compile(rule)));
        }

        // Claim each fact for at most one commitment: the pin first, then the latest declaration
        // whose rules and sign match.
        Map<String, List<Assigned>> assignedByCommitment = new TreeMap<>();
        for (CurrentFact fact : facts) {
            String pinnedTo = pinByFact.get(fact.externalId());
            if (pinnedTo != null) {
                Commitment pinned = committedById.get(pinnedTo);
                if (pinned != null && signMatches(pinned, fact)) {
                    assignedByCommitment.computeIfAbsent(pinnedTo, k -> new ArrayList<>())
                        .add(new Assigned(fact, BY_PIN));
                    continue;
                }
            }
            for (Commitment candidate : byDeclaration) {
                if (!signMatches(candidate, fact)) {
                    continue;
                }
                List<CompiledRule> candidateRules = rulesByCommitment.get(candidate.commitmentId());
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
            List<Slot> slots = new ArrayList<>();
            if (commitment.cadence() == Cadence.IRREGULAR) {
                irregular(assigned, slots);
            } else {
                materialise(commitment, asOfDate, slots);
                applySettles(commitment, settleByCommitment.get(commitment.commitmentId()), slots);
                allocate(commitment, assigned, slots);
                slots.sort(Comparator.comparing((Slot s) -> s.dueDate)
                    .thenComparing(s -> s.offSchedule));
            }
            for (Slot slot : slots) {
                occurrences.add(slot.toOccurrence(commitment.commitmentId()));
            }
            arrears.add(arrears(commitment, slots, asOfDate));
        }
        return new CommitmentMatch(occurrences, arrears);
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
        int offset = Math.min(commitment.cadence().days() / 2, WINDOW_MAX_DAYS);

        long k = 0;
        LocalDate due = anchor;
        while (due.isBefore(from)) {
            k++;
            due = addPeriods(anchor, commitment.cadence(), k);
        }
        while (!due.isAfter(horizon)) {
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
     * Allocate each assigned fact (§2.9). A rule fact goes to the commitment's open occurrences
     * ({@code due}, {@code missed}, {@code partial}) oldest first, each taking up to its expected
     * amount — the backlog clears from the front, and a surplus pre-pays the future occurrences
     * already materialised. A pinned fact satisfies the nearest open occurrence within one full
     * cadence and never re-anchors (§10.15). Whatever is left over becomes an {@code off_schedule}
     * occurrence at the fact's date: nothing is swallowed.
     */
    private static void allocate(Commitment commitment, List<Assigned> facts, List<Slot> slots) {
        for (Assigned assigned : facts) {
            CurrentFact fact = assigned.fact;
            long remaining = Math.abs(fact.fact().amount());
            if (BY_PIN.equals(assigned.matchedBy)) {
                Slot nearest = nearestOpen(slots, commitment.cadence().days(), fact.fact().date());
                if (nearest != null) {
                    remaining = allocateTo(commitment, nearest, assigned, remaining);
                }
                if (remaining > 0) {
                    offSchedule(assigned, slots, remaining);
                }
                continue;
            }
            for (Slot slot : slots) {
                if (remaining == 0) {
                    break;
                }
                if (!slot.open()) {
                    continue;
                }
                remaining = allocateTo(commitment, slot, assigned, remaining);
            }
            if (remaining > 0) {
                offSchedule(assigned, slots, remaining);
            }
        }
    }

    /**
     * Take up to the slot's still-expected amount from the fact. Returns the fact's unallocated
     * remainder. The occurrence becomes {@code occurred} once covered within {@code max(2%, 50¢)}
     * of its expected amount — the fact id and the allocated total land on the row — and
     * {@code partial} otherwise, carrying only what was allocated. With no price anywhere to
     * split against (a declared commitment with no amount), the fact settles the occurrence at its
     * full value: one fact, one occurrence, rather than an invented division.
     */
    private static long allocateTo(Commitment commitment, Slot slot, Assigned assigned,
                                   long remaining) {
        CurrentFact fact = assigned.fact;
        long sign = fact.fact().amount() < 0 ? -1 : 1;
        Long expected = expected(commitment, slot.dueDate);
        if (expected == null || expected == 0) {
            slot.status = OccurrenceStatus.OCCURRED;
            slot.amount = sign * remaining;
            slot.matchedExternalId = fact.externalId();
            slot.matchedDate = fact.fact().date();
            slot.matchedBy = assigned.matchedBy;
            return 0;
        }
        long expectedAbs = Math.abs(expected);
        long take = Math.min(remaining, expectedAbs - slot.allocated);
        long total = slot.allocated + take;
        long tolerance = Math.max(FULL_MIN_CENTS, Math.round(expectedAbs * FULL_FRACTION));
        slot.amount = sign * total;
        slot.matchedExternalId = fact.externalId();
        slot.matchedDate = fact.fact().date();
        slot.matchedBy = assigned.matchedBy;
        if (total >= expectedAbs - tolerance) {
            slot.status = OccurrenceStatus.OCCURRED;
        } else {
            slot.status = OccurrenceStatus.PARTIAL;
            slot.allocated = total;
        }
        return remaining - take;
    }

    /** The nearest open occurrence within one full cadence of the pinned fact (§10.15). */
    private static Slot nearestOpen(List<Slot> slots, int cadenceDays, LocalDate date) {
        Slot found = null;
        long best = Long.MAX_VALUE;
        for (Slot slot : slots) {
            if (!slot.open()) {
                continue;
            }
            long distance = Math.abs(ChronoUnit.DAYS.between(slot.dueDate, date));
            if (distance > cadenceDays) {
                continue;
            }
            if (found == null || distance < best
                || (distance == best && slot.dueDate.isBefore(found.dueDate))) {
                best = distance;
                found = slot;
            }
        }
        return found;
    }

    /**
     * Record the unallocated remainder — or a fact that found no occurrence — at its own date;
     * the occurrence table's natural overflow, never a silent adjustment.
     */
    private static void offSchedule(Assigned assigned, List<Slot> slots, long amount) {
        CurrentFact fact = assigned.fact;
        Slot slot = new Slot(fact.fact().date(), null, null, OccurrenceStatus.OCCURRED);
        slot.offSchedule = true;
        slot.amount = fact.fact().amount() < 0 ? -amount : amount;
        slot.matchedExternalId = fact.externalId();
        slot.matchedDate = fact.fact().date();
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
     * The commitment's backlog (§2.9): every closed-window occurrence still short is counted, and
     * {@code lapsed} mirrors the most recent closed-window occurrence — the current red state,
     * never the older backlog.
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
            if (slot.status == OccurrenceStatus.MISSED || slot.status == OccurrenceStatus.PARTIAL) {
                count++;
                amount += shortfall(commitment, slot);
            }
            if (slot.windowEnd.isBefore(asOfDate)
                && (recent == null || slot.dueDate.isAfter(recent.dueDate))) {
                recent = slot;
            }
        }
        boolean lapsed = recent != null
            && (recent.status == OccurrenceStatus.MISSED
                || recent.status == OccurrenceStatus.PARTIAL);
        return new CommitmentArrears(commitment.commitmentId(), count, amount, lapsed);
    }

    /** The expected amount still short on one occurrence, signed like its price point. */
    private static long shortfall(Commitment commitment, Slot slot) {
        Long expected = expected(commitment, slot.dueDate);
        if (expected == null) {
            return 0;
        }
        long shortAbs = Math.abs(expected) - slot.allocated;
        return shortAbs <= 0 ? 0 : Long.signum(expected) * shortAbs;
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

    private static boolean anyMatch(List<CompiledRule> rules, CurrentFact fact) {
        for (CompiledRule rule : rules) {
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

    /** The {@code categories.yaml} pattern convention ({@code RuleSet.compile}). */
    private static Pattern compile(CommitmentRule rule) {
        try {
            return Pattern.compile(rule.match(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("commitment '" + rule.commitmentId()
                + "': bad regex '" + rule.match() + "': " + e.getDescription(), e);
        }
    }

    /** One assigned fact; {@code matchedBy} is {@code pin} or {@code rule}. */
    private record Assigned(CurrentFact fact, String matchedBy) {}

    /** One rule with its compiled pattern, matched over {@link Clean#clean}. */
    private record CompiledRule(CommitmentRule rule, Pattern pattern) {

        boolean matches(CurrentFact fact) {
            if (rule.accountRef() != null && !rule.accountRef().isBlank()
                && !rule.accountRef().equals(fact.fact().accountRef())) {
                return false;
            }
            return pattern.matcher(Clean.clean(fact.fact().rawDescription())).find();
        }
    }

    /**
     * One occurrence while it is being built. {@code amount} is the allocated amount in cents,
     * {@code allocated} its magnitude (what the arrears subtract), and the window is null for
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
        long allocated;

        Slot(LocalDate dueDate, LocalDate windowStart, LocalDate windowEnd, OccurrenceStatus status) {
            this.dueDate = dueDate;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.status = status;
        }

        boolean open() {
            return status == OccurrenceStatus.DUE
                || status == OccurrenceStatus.MISSED
                || status == OccurrenceStatus.PARTIAL;
        }

        CommitmentOccurrence toOccurrence(String commitmentId) {
            return new CommitmentOccurrence(commitmentId, dueDate, status, windowStart, windowEnd,
                matchedExternalId, matchedDate, matchedBy, offSchedule, settleN, amount, null)
                .hashed();
        }
    }
}
