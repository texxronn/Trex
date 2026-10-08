package trex.v2.core;

import org.junit.jupiter.api.Test;
import trex.v2.core.derive.AmountKind;
import trex.v2.core.derive.Cadence;
import trex.v2.core.derive.CategoryOrigin;
import trex.v2.core.derive.Commitment;
import trex.v2.core.derive.CommitmentArrears;
import trex.v2.core.derive.CommitmentKind;
import trex.v2.core.derive.CommitmentMatch;
import trex.v2.core.derive.CommitmentOccurrence;
import trex.v2.core.derive.CommitmentOrigin;
import trex.v2.core.derive.CommitmentPin;
import trex.v2.core.derive.CommitmentRule;
import trex.v2.core.derive.CommitmentSettle;
import trex.v2.core.derive.CommitmentStatus;
import trex.v2.core.derive.Commitments;
import trex.v2.core.derive.CurrentFact;
import trex.v2.core.derive.LegState;
import trex.v2.core.derive.OccurrenceStatus;
import trex.v2.core.derive.Role;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 2 acceptance for the occurrence matcher (V2-COMMITMENTS-PLAN.md §2.5, §2.9, §6):
 * calendar generation, rule and pin assignment, windows and statuses, catch-up allocation,
 * arrears and determinism — hand-built facts, no I/O, no clock. The worked acceptance cases
 * (§7.4, §7.5, §7.8, §7.11, §7.12, §7.13) are reproduced here rather than read from the index.
 */
class CommitmentMatchTest {

    private static final Instant ASOF = Instant.parse("2026-04-01T00:00:00Z");

    // ---- fixtures ---------------------------------------------------------------------------

    private static CurrentFact fact(long n, String account, LocalDate date, long amount, String raw) {
        return fact(n, account, date, amount, raw, Role.TRANSACTION, LegState.EXTERNAL);
    }

    private static CurrentFact fact(long n, String account, LocalDate date, long amount, String raw,
                                    Role role, LegState leg) {
        Fact f = new Fact(n, "id-" + n, account, date, amount, 0, raw, null, 0,
            Observation.POSTED, "test", Provenance.BANK, null, "test/1",
            Instant.parse("2026-03-31T00:00:00Z"));
        return new CurrentFact(f, role, null, leg, leg == LegState.MATCHED ? "TRF-" + n : null,
            null, CategoryOrigin.NONE, null, null);
    }

    private static Commitment declared(String id, String direction, Cadence cadence,
                                       LocalDate anchor, long amount, long declaredN) {
        return new Commitment(id, null, id, CommitmentOrigin.DECLARED, direction, cadence,
            AmountKind.FIXED, CommitmentKind.BILL, CommitmentStatus.ACTIVE, anchor, null, anchor,
            amount, null, null, null,
            List.of(new Commitment.PriceStep(anchor, amount, null, null)), null, null, 0, 1.0,
            false, 0, null, declaredN, null, null);
    }

    private static Commitment declared(String id, String direction, Cadence cadence,
                                       LocalDate anchor, long currentAmount,
                                       List<Commitment.PriceStep> steps, long declaredN) {
        return new Commitment(id, null, id, CommitmentOrigin.DECLARED, direction, cadence,
            AmountKind.FIXED, CommitmentKind.BILL, CommitmentStatus.ACTIVE, anchor, null, anchor,
            currentAmount, null, null, null, steps, null, null, 0, 1.0, false, 0, null,
            declaredN, null, null);
    }

    private static Commitment declared(String id, String direction, Cadence cadence,
                                       LocalDate anchor, long amount, AmountKind amountKind,
                                       long declaredN) {
        return new Commitment(id, null, id, CommitmentOrigin.DECLARED, direction, cadence,
            amountKind, CommitmentKind.BILL, CommitmentStatus.ACTIVE, anchor, null, anchor,
            amount, null, null, null,
            List.of(new Commitment.PriceStep(anchor, amount, null, null)), null, null, 0, 1.0,
            false, 0, null, declaredN, null, null);
    }

    private static CommitmentRule rule(String commitmentId, String match) {
        return new CommitmentRule(commitmentId, match, null, 1L);
    }

    private static CommitmentMatch run(List<Commitment> commitments, List<CommitmentRule> rules,
                                       List<CurrentFact> facts, Instant asOf) {
        return Commitments.match(commitments, rules, facts, List.of(), List.of(), asOf);
    }

    private static CommitmentOccurrence at(CommitmentMatch match, String commitmentId,
                                           LocalDate dueDate) {
        return match.occurrences().stream()
            .filter(o -> o.commitmentId().equals(commitmentId) && o.dueDate().equals(dueDate))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no occurrence " + commitmentId + " @ " + dueDate
                + ": " + match.occurrences()));
    }

    private static List<LocalDate> scheduled(CommitmentMatch match, String commitmentId) {
        return match.occurrences().stream()
            .filter(o -> o.commitmentId().equals(commitmentId) && o.windowStart() != null)
            .map(CommitmentOccurrence::dueDate)
            .toList();
    }

    private static CommitmentArrears arrears(CommitmentMatch match, String commitmentId) {
        return match.arrears().stream().filter(a -> a.commitmentId().equals(commitmentId))
            .findFirst().orElseThrow();
    }

    // ---- generation -------------------------------------------------------------------------

    @Test
    void monthlyAnchorOnThe31stClampsInFebruaryAndReturnsToThe31st() {
        Commitment netflix = declared("netflix", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 1, 31), -999L, 1);
        CommitmentMatch match = run(List.of(netflix), List.of(), List.of(),
            Instant.parse("2026-03-15T00:00:00Z"));

        assertEquals(List.of(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
            LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30), LocalDate.of(2026, 5, 31)),
            scheduled(match, "netflix"), "calendar arithmetic from the anchor, not +30 days");
        CommitmentOccurrence february = at(match, "netflix", LocalDate.of(2026, 2, 28));
        assertEquals(LocalDate.of(2026, 2, 21), february.windowStart());
        assertEquals(LocalDate.of(2026, 3, 7), february.windowEnd());
        assertEquals(OccurrenceStatus.MISSED, february.status(), "the February window closed");
        assertEquals(OccurrenceStatus.DUE, at(match, "netflix", LocalDate.of(2026, 3, 31)).status());
        assertEquals("netflix", arrears(match, "netflix").commitmentId());
        assertEquals(2, arrears(match, "netflix").count(), "January and February are behind");
    }

    // ---- matching ---------------------------------------------------------------------------

    @Test
    void aFactInsideTheWindowMatchesByRuleWithItsId() {
        Commitment netflix = declared("netflix", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 3, 15), -5000L, 1);
        CommitmentRule netflixRule = rule("netflix", "NETFLIX");
        CurrentFact charge = fact(1, "bw-credit-card", LocalDate.of(2026, 3, 20), -5000L,
            "PAYPAL *NETFLIX AUS");

        CommitmentMatch match = run(List.of(netflix), List.of(netflixRule), List.of(charge), ASOF);

        CommitmentOccurrence march = at(match, "netflix", LocalDate.of(2026, 3, 15));
        assertEquals(OccurrenceStatus.OCCURRED, march.status());
        assertEquals("id-1", march.matchedExternalId());
        assertEquals(LocalDate.of(2026, 3, 20), march.matchedDate());
        assertEquals("rule", march.matchedBy());
        assertEquals(-5000L, march.amount().longValue(), "the fact's amount, not a rejection");
        assertEquals(OccurrenceStatus.DUE, at(match, "netflix", LocalDate.of(2026, 4, 15)).status());
        assertEquals(0, arrears(match, "netflix").count());
        assertFalse(arrears(match, "netflix").lapsed());
    }

    @Test
    void aClosedWindowWithoutAMatchIsMissedAndALaterFactFlipsIt() {
        Commitment bill = declared("bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 1, 15), -10000L, 1);
        CommitmentRule billRule = rule("bill", "ACME");
        CurrentFact january = fact(1, "bw-credit-card", LocalDate.of(2026, 1, 15), -10000L,
            "ACME BILL");

        CommitmentMatch first = run(List.of(bill), List.of(billRule), List.of(january), ASOF);
        assertEquals(OccurrenceStatus.OCCURRED, at(first, "bill", LocalDate.of(2026, 1, 15)).status());
        assertEquals(OccurrenceStatus.MISSED, at(first, "bill", LocalDate.of(2026, 2, 15)).status());
        assertEquals(OccurrenceStatus.MISSED, at(first, "bill", LocalDate.of(2026, 3, 15)).status());
        assertEquals(2, arrears(first, "bill").count());
        assertEquals(-20000L, arrears(first, "bill").amount());
        assertTrue(arrears(first, "bill").lapsed(), "the latest closed window is missed");

        // The same derive instant with one more fact: the February window takes the late charge.
        CurrentFact february = fact(2, "bw-credit-card", LocalDate.of(2026, 2, 20), -10000L,
            "ACME BILL");
        CommitmentMatch second =
            run(List.of(bill), List.of(billRule), List.of(january, february), ASOF);
        CommitmentOccurrence flipped = at(second, "bill", LocalDate.of(2026, 2, 15));
        assertEquals(OccurrenceStatus.OCCURRED, flipped.status());
        assertEquals("id-2", flipped.matchedExternalId());
        assertEquals(OccurrenceStatus.MISSED, at(second, "bill", LocalDate.of(2026, 3, 15)).status());
        assertEquals(1, arrears(second, "bill").count());
        assertEquals(-10000L, arrears(second, "bill").amount());
    }

    @Test
    void noopFactsAndWrongSignFactsDoNotMatch() {
        Commitment bill = declared("bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 3, 15), -10000L, 1);
        CommitmentRule billRule = rule("bill", "ACME");
        CurrentFact noop = fact(1, "cash-ron", LocalDate.of(2026, 3, 16), -10000L, "ACME BILL",
            Role.NOOP, LegState.EXTERNAL);
        CurrentFact credit = fact(2, "bw-credit-card", LocalDate.of(2026, 3, 17), 10000L,
            "ACME REFUND");

        CommitmentMatch match =
            run(List.of(bill), List.of(billRule), List.of(noop, credit), ASOF);

        assertEquals(OccurrenceStatus.MISSED, at(match, "bill", LocalDate.of(2026, 3, 15)).status());
        assertNull(at(match, "bill", LocalDate.of(2026, 3, 15)).matchedExternalId(),
            "a noop is not a posting and a refund has the wrong sign");
        assertEquals(1, arrears(match, "bill").count());
    }

    @Test
    void overlappingCommitmentsResolveToTheLatestDeclaration() {
        Commitment older = declared("a-plan", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 3, 10), -10000L, 1);
        Commitment newer = declared("b-plan", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 3, 12), -10000L, 2);
        CurrentFact charge = fact(1, "bw-credit-card", LocalDate.of(2026, 3, 11), -10000L,
            "PAYPAL *STAN");

        CommitmentMatch match = run(List.of(older, newer),
            List.of(rule("a-plan", "STAN"), rule("b-plan", "STAN")), List.of(charge), ASOF);

        assertEquals("id-1", at(match, "b-plan", LocalDate.of(2026, 3, 12)).matchedExternalId(),
            "declaredN 2 wins the overlap");
        assertNull(at(match, "a-plan", LocalDate.of(2026, 3, 10)).matchedExternalId());
        assertEquals(OccurrenceStatus.MISSED, at(match, "a-plan", LocalDate.of(2026, 3, 10)).status());
    }

    // ---- pins -------------------------------------------------------------------------------

    @Test
    void aPinnedFactOverridesTheRulesAndLandsOnItsCommitment() {
        Commitment rulesBill = declared("rules-bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 3, 10), -10000L, 1);
        Commitment pinnedBill = declared("pinned-bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 3, 12), -10000L, 2);
        CurrentFact charge = fact(1, "cba-netsaver", LocalDate.of(2026, 3, 11), -10000L,
            "BPAY 123456");

        CommitmentMatch match = Commitments.match(List.of(rulesBill, pinnedBill),
            List.of(rule("rules-bill", "BPAY")), List.of(charge),
            List.of(new CommitmentPin("id-1", "pinned-bill")), List.of(), ASOF);

        CommitmentOccurrence pinned = at(match, "pinned-bill", LocalDate.of(2026, 3, 12));
        assertEquals(OccurrenceStatus.OCCURRED, pinned.status());
        assertEquals("pin", pinned.matchedBy());
        assertEquals("id-1", pinned.matchedExternalId());
        assertNull(at(match, "rules-bill", LocalDate.of(2026, 3, 10)).matchedExternalId(),
            "the pin is a person's override of the rules");
    }

    @Test
    void aPinAllocatesLikeARuleOldestFirst() {
        Commitment bill = declared("bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 1, 10), -10000L, 1);
        CurrentFact lump = fact(1, "cba-netsaver", LocalDate.of(2026, 4, 3), -30000L,
            "BPAY 123456");

        CommitmentMatch match = Commitments.match(List.of(bill), List.of(), List.of(lump),
            List.of(new CommitmentPin("id-1", "bill")), List.of(),
            Instant.parse("2026-04-05T00:00:00Z"));

        for (LocalDate due : List.of(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 2, 10),
                LocalDate.of(2026, 3, 10))) {
            CommitmentOccurrence occurrence = at(match, "bill", due);
            assertEquals(OccurrenceStatus.OCCURRED, occurrence.status());
            assertEquals("pin", occurrence.matchedBy());
            assertEquals("id-1", occurrence.matchedExternalId());
            assertEquals(-10000L, occurrence.amount().longValue(),
                "the one fact covers three periods, oldest first");
        }
        assertEquals(OccurrenceStatus.DUE, at(match, "bill", LocalDate.of(2026, 4, 10)).status());
        assertEquals(0, arrears(match, "bill").count());
    }

    @Test
    void unpinningReleasesTheFactBackToTheRules() {
        Commitment rulesBill = declared("rules-bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 3, 15), -10000L, 1);
        Commitment pinnedBill = declared("pinned-bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 3, 15), -10000L, 2);
        CurrentFact charge = fact(1, "cba-netsaver", LocalDate.of(2026, 3, 20), -10000L,
            "BPAY 123456");

        CommitmentMatch pinned = Commitments.match(List.of(rulesBill, pinnedBill),
            List.of(rule("rules-bill", "BPAY")), List.of(charge),
            List.of(new CommitmentPin("id-1", "pinned-bill")), List.of(), ASOF);
        assertEquals("pin", at(pinned, "pinned-bill", LocalDate.of(2026, 3, 15)).matchedBy());

        CommitmentMatch unpinned = Commitments.match(List.of(rulesBill, pinnedBill),
            List.of(rule("rules-bill", "BPAY")), List.of(charge), List.of(), List.of(), ASOF);
        CommitmentOccurrence byRule = at(unpinned, "rules-bill", LocalDate.of(2026, 3, 15));
        assertEquals(OccurrenceStatus.OCCURRED, byRule.status());
        assertEquals("rule", byRule.matchedBy());
        assertEquals("id-1", byRule.matchedExternalId());
        assertNull(at(unpinned, "pinned-bill", LocalDate.of(2026, 3, 15)).matchedExternalId(),
            "absent from the pin list, the fact follows the rules");
    }

    // ---- settles and irregular --------------------------------------------------------------

    @Test
    void aSettleMarksTheOccurrenceSettledAndClearsArrears() {
        Commitment bill = declared("bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 1, 15), -10000L, 1);
        CommitmentMatch match = Commitments.match(List.of(bill), List.of(), List.of(),
            List.of(), List.of(new CommitmentSettle("bill", LocalDate.of(2026, 1, 15), 7),
                new CommitmentSettle("bill", LocalDate.of(2026, 2, 15), 8)), ASOF);

        CommitmentOccurrence january = at(match, "bill", LocalDate.of(2026, 1, 15));
        assertEquals(OccurrenceStatus.SETTLED, january.status());
        assertEquals(7L, january.settleN().longValue());
        assertEquals(-10000L, january.amount().longValue(), "settled states the expected amount");
        assertEquals(OccurrenceStatus.SETTLED, at(match, "bill", LocalDate.of(2026, 2, 15)).status());
        assertEquals(1, arrears(match, "bill").count(), "March is still behind");
        assertEquals(-10000L, arrears(match, "bill").amount());
    }

    @Test
    void anIrregularCommitmentRecordsEachMatchingFactAtItsOwnDate() {
        Commitment notice = declared("notice", Commitment.OUT, Cadence.IRREGULAR,
            LocalDate.of(2026, 1, 7), -5000L, 1);
        CommitmentRule noticeRule = rule("notice", "COUNCIL NOTICE");
        List<CurrentFact> facts = List.of(
            fact(1, "bw-credit-card", LocalDate.of(2026, 1, 7), -5000L, "COUNCIL NOTICE ONE"),
            fact(2, "bw-credit-card", LocalDate.of(2026, 3, 19), -5000L, "COUNCIL NOTICE TWO"),
            fact(3, "bw-credit-card", LocalDate.of(2026, 5, 31), -5000L, "COUNCIL NOTICE THREE"),
            fact(4, "bw-credit-card", LocalDate.of(2026, 4, 1), -1000L, "COLES 1234"),
            fact(5, "bw-credit-card", LocalDate.of(2024, 2, 1), -5000L, "COUNCIL NOTICE ZERO"));

        CommitmentMatch match = run(List.of(notice), List.of(noticeRule), facts,
            Instant.parse("2026-06-01T00:00:00Z"));

        assertEquals(List.of(LocalDate.of(2024, 2, 1), LocalDate.of(2026, 1, 7),
            LocalDate.of(2026, 3, 19), LocalDate.of(2026, 5, 31)),
            match.occurrences().stream().map(CommitmentOccurrence::dueDate).toList(),
            "irregular keeps every matching fact — no calendar to bound against");
        for (CommitmentOccurrence occurrence : match.occurrences()) {
            assertEquals("notice", occurrence.commitmentId());
            assertEquals(OccurrenceStatus.OCCURRED, occurrence.status());
            assertFalse(occurrence.offSchedule(), "the natural form");
            assertNull(occurrence.windowStart(), "irregular has no window at all");
            assertEquals("rule", occurrence.matchedBy());
        }
        assertEquals(0, arrears(match, "notice").count());
        assertEquals(0L, arrears(match, "notice").amount());
        assertFalse(arrears(match, "notice").lapsed(), "irregular is never lapsed");
    }

    @Test
    void aMatchedTransferLegMatchesLikeAnyOtherFact() {
        Commitment loan = declared("home-loan", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 3, 5), -250000L, 1);
        CurrentFact repayment = fact(1, "cba-smartaccess", LocalDate.of(2026, 3, 5), -250000L,
            "HOME LOAN REPAYMENT", Role.TRANSACTION, LegState.MATCHED);

        CommitmentMatch match = run(List.of(loan), List.of(rule("home-loan", "HOME LOAN REPAYMENT")),
            List.of(repayment), ASOF);

        CommitmentOccurrence march = at(match, "home-loan", LocalDate.of(2026, 3, 5));
        assertEquals(OccurrenceStatus.OCCURRED, march.status());
        assertEquals("id-1", march.matchedExternalId());
        assertEquals(-250000L, march.amount().longValue());
    }

    // ---- determinism -------------------------------------------------------------------------

    @Test
    void sameInputsGiveIdenticalOccurrencesAndHashes() {
        List<Commitment> commitments = new ArrayList<>(List.of(
            declared("a-bill", Commitment.OUT, Cadence.MONTHLY, LocalDate.of(2026, 2, 15),
                -10000L, 1),
            declared("b-income", Commitment.IN, Cadence.MONTHLY, LocalDate.of(2026, 1, 20),
                150000L, 2)));
        List<CommitmentRule> rules = new ArrayList<>(List.of(
            rule("a-bill", "ACME BILL"),
            rule("b-income", "PAYROLL")));
        List<CurrentFact> facts = new ArrayList<>(List.of(
            fact(1, "bw-credit-card", LocalDate.of(2026, 2, 16), -10000L, "ACME BILL"),
            fact(2, "ing-salary", LocalDate.of(2026, 2, 20), 150000L, "PAYROLL ACME PTY"),
            fact(3, "bw-credit-card", LocalDate.of(2026, 3, 17), -10000L, "ACME BILL")));
        List<CommitmentPin> pins = new ArrayList<>(List.of(new CommitmentPin("id-3", "a-bill")));
        List<CommitmentSettle> settles = new ArrayList<>(
            List.of(new CommitmentSettle("b-income", LocalDate.of(2026, 1, 20), 9)));

        CommitmentMatch first = Commitments.match(commitments, rules, facts, pins, settles, ASOF);
        CommitmentMatch second = Commitments.match(commitments, rules, facts, pins, settles, ASOF);
        assertEquals(first, second);
        assertEquals(first.occurrences().stream().map(CommitmentOccurrence::stateHash).toList(),
            second.occurrences().stream().map(CommitmentOccurrence::stateHash).toList());

        Collections.reverse(commitments);
        Collections.reverse(rules);
        Collections.reverse(facts);
        Collections.reverse(pins);
        Collections.reverse(settles);
        assertEquals(first, Commitments.match(commitments, rules, facts, pins, settles, ASOF),
            "input order must not matter");
        for (CommitmentOccurrence occurrence : first.occurrences()) {
            assertEquals("sha256:".length() + 64, occurrence.stateHash().length());
        }
        assertEquals(2, first.occurrences().stream()
            .filter(o -> o.commitmentId().equals("a-bill") && o.matchedExternalId() != null)
            .count());
    }

    // ---- catch-up allocation and arrears ----------------------------------------------------

    @Test
    void aThreePeriodLumpClearsThreeOccurrencesOldestFirstWithNoArrears() {
        Commitment bill = declared("bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 1, 10), -10000L, 1);
        CurrentFact lump = fact(1, "cba-netsaver", LocalDate.of(2026, 4, 3), -30000L, "BILL PAYMENT");

        CommitmentMatch match = run(List.of(bill), List.of(rule("bill", "BILL")), List.of(lump),
            Instant.parse("2026-04-05T00:00:00Z"));

        for (LocalDate due : List.of(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 2, 10),
                LocalDate.of(2026, 3, 10))) {
            CommitmentOccurrence occurrence = at(match, "bill", due);
            assertEquals(OccurrenceStatus.OCCURRED, occurrence.status());
            assertEquals("id-1", occurrence.matchedExternalId(),
                "the shared fact id says all three were one payment");
            assertEquals(-10000L, occurrence.amount().longValue());
        }
        assertEquals(OccurrenceStatus.DUE, at(match, "bill", LocalDate.of(2026, 4, 10)).status());
        assertEquals(0, arrears(match, "bill").count(), "the backlog cleared from the front");
        assertEquals(0L, arrears(match, "bill").amount());
        assertFalse(arrears(match, "bill").lapsed());
    }

    @Test
    void aShortLumpLeavesTheNextOccurrencePartialAndTheRemainderInArrears() {
        Commitment bill = declared("bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 2, 10), -10000L, 1);
        CurrentFact shortLump = fact(1, "cba-netsaver", LocalDate.of(2026, 4, 3), -15000L,
            "BILL PAYMENT");

        CommitmentMatch match = run(List.of(bill), List.of(rule("bill", "BILL")),
            List.of(shortLump), Instant.parse("2026-04-05T00:00:00Z"));

        assertEquals(OccurrenceStatus.OCCURRED, at(match, "bill", LocalDate.of(2026, 2, 10)).status());
        CommitmentOccurrence partial = at(match, "bill", LocalDate.of(2026, 3, 10));
        assertEquals(OccurrenceStatus.PARTIAL, partial.status());
        assertEquals(-5000L, partial.amount().longValue(), "only what was allocated");
        assertEquals("id-1", partial.matchedExternalId());
        assertEquals(1, arrears(match, "bill").count());
        assertEquals(-5000L, arrears(match, "bill").amount(), "the remainder still short");
        assertTrue(arrears(match, "bill").lapsed());
    }

    @Test
    void aSurplusPrePaysFutureOccurrencesAndTheLeftoverIsOffSchedule() {
        Commitment bill = declared("bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 4, 10), -10000L, 1);
        CurrentFact prepay = fact(1, "cba-netsaver", LocalDate.of(2026, 5, 4), -45000L,
            "BILL PREPAY");

        CommitmentMatch match = run(List.of(bill), List.of(rule("bill", "BILL")),
            List.of(prepay), Instant.parse("2026-05-05T00:00:00Z"));

        for (LocalDate due : List.of(LocalDate.of(2026, 4, 10), LocalDate.of(2026, 5, 10),
                LocalDate.of(2026, 6, 10), LocalDate.of(2026, 7, 10))) {
            assertEquals(OccurrenceStatus.OCCURRED, at(match, "bill", due).status(), due.toString());
        }
        CommitmentOccurrence leftover = at(match, "bill", LocalDate.of(2026, 5, 4));
        assertTrue(leftover.offSchedule(), "nothing is swallowed");
        assertEquals("id-1", leftover.matchedExternalId());
        assertEquals(-5000L, leftover.amount().longValue());
        assertEquals(0, arrears(match, "bill").count());
    }

    @Test
    void aCatchUpFactSkipsSettledOccurrences() {
        Commitment bill = declared("bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 1, 15), -10000L, 1);
        CurrentFact payment = fact(1, "cba-netsaver", LocalDate.of(2026, 3, 30), -15000L,
            "BILL PAYMENT");

        CommitmentMatch match = Commitments.match(List.of(bill), List.of(rule("bill", "BILL")),
            List.of(payment), List.of(),
            List.of(new CommitmentSettle("bill", LocalDate.of(2026, 1, 15), 7),
                new CommitmentSettle("bill", LocalDate.of(2026, 2, 15), 8)),
            Instant.parse("2026-04-05T00:00:00Z"));

        assertEquals(OccurrenceStatus.SETTLED, at(match, "bill", LocalDate.of(2026, 1, 15)).status());
        assertEquals(OccurrenceStatus.SETTLED, at(match, "bill", LocalDate.of(2026, 2, 15)).status());
        assertEquals(OccurrenceStatus.OCCURRED, at(match, "bill", LocalDate.of(2026, 3, 15)).status());
        CommitmentOccurrence partial = at(match, "bill", LocalDate.of(2026, 4, 15));
        assertEquals(OccurrenceStatus.PARTIAL, partial.status());
        assertEquals(-5000L, partial.amount().longValue());
        assertEquals(1, arrears(match, "bill").count());
        assertEquals(-5000L, arrears(match, "bill").amount());
    }

    @Test
    void theExpectedAmountFollowsThePriceSteps() {
        List<Commitment.PriceStep> steps = List.of(
            new Commitment.PriceStep(LocalDate.of(2026, 2, 10), -10000L, null, null),
            new Commitment.PriceStep(LocalDate.of(2026, 4, 10), -12000L, -10000L, 20.0));
        Commitment bill = declared("bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 1, 10), -12000L, steps, 1);
        CurrentFact lump = fact(1, "cba-netsaver", LocalDate.of(2026, 4, 3), -33000L,
            "BILL PAYMENT");

        CommitmentMatch match = run(List.of(bill), List.of(rule("bill", "BILL")), List.of(lump),
            Instant.parse("2026-05-05T00:00:00Z"));

        // January predates the first point and expects the first point's amount.
        assertEquals(OccurrenceStatus.OCCURRED, at(match, "bill", LocalDate.of(2026, 1, 10)).status());
        assertEquals(-10000L, at(match, "bill", LocalDate.of(2026, 1, 10)).amount().longValue());
        assertEquals(OccurrenceStatus.OCCURRED, at(match, "bill", LocalDate.of(2026, 3, 10)).status());
        CommitmentOccurrence partial = at(match, "bill", LocalDate.of(2026, 4, 10));
        assertEquals(OccurrenceStatus.PARTIAL, partial.status());
        assertEquals(-3000L, partial.amount().longValue(), "April expects the $120 step");
        assertEquals(-9000L, arrears(match, "bill").amount());
        assertEquals(OccurrenceStatus.DUE, at(match, "bill", LocalDate.of(2026, 5, 10)).status());
    }

    @Test
    void lapsedTracksTheCurrentOccurrenceNotTheBacklog() {
        Commitment bill = declared("bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 1, 15), -10000L, 1);

        CommitmentMatch match = Commitments.match(List.of(bill), List.of(), List.of(),
            List.of(),
            List.of(new CommitmentSettle("bill", LocalDate.of(2026, 1, 15), 7),
                new CommitmentSettle("bill", LocalDate.of(2026, 3, 15), 8)),
            Instant.parse("2026-04-05T00:00:00Z"));

        assertEquals(OccurrenceStatus.SETTLED, at(match, "bill", LocalDate.of(2026, 3, 15)).status());
        assertEquals(1, arrears(match, "bill").count(), "February is still behind");
        assertEquals(-10000L, arrears(match, "bill").amount());
        assertFalse(arrears(match, "bill").lapsed(),
            "the most recent closed window is settled, not red");
    }

    @Test
    void aPinWithNoOpenOccurrenceIsOffSchedule() {
        Commitment bill = declared("bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 3, 10), -10000L, 1);
        CurrentFact bpay = fact(1, "cba-netsaver", LocalDate.of(2026, 3, 20), -10000L,
            "BPAY 999888");

        CommitmentMatch match = Commitments.match(List.of(bill), List.of(), List.of(bpay),
            List.of(new CommitmentPin("id-1", "bill")),
            List.of(new CommitmentSettle("bill", LocalDate.of(2026, 3, 10), 7),
                new CommitmentSettle("bill", LocalDate.of(2026, 4, 10), 8),
                new CommitmentSettle("bill", LocalDate.of(2026, 5, 10), 9),
                new CommitmentSettle("bill", LocalDate.of(2026, 6, 10), 10)),
            Instant.parse("2026-04-05T00:00:00Z"));

        CommitmentOccurrence off = at(match, "bill", LocalDate.of(2026, 3, 20));
        assertTrue(off.offSchedule(), "every occurrence is settled; nothing is open");
        assertEquals(OccurrenceStatus.OCCURRED, off.status());
        assertEquals("pin", off.matchedBy());
        assertEquals(-10000L, off.amount().longValue());
        assertNull(off.windowStart());
        assertEquals(List.of(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 4, 10),
            LocalDate.of(2026, 5, 10), LocalDate.of(2026, 6, 10)), scheduled(match, "bill"),
            "the pin never moves the anchor");
        assertEquals(0, arrears(match, "bill").count());
    }

    @Test
    void twoOffScheduleFactsOnOneDateAreOneRowWithTheSummedAmount() {
        Commitment bill = declared("bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 3, 10), -10000L, 1);
        CurrentFact first = fact(1, "cba-netsaver", LocalDate.of(2026, 3, 20), -10000L, "BPAY 111111");
        CurrentFact second = fact(2, "cba-netsaver", LocalDate.of(2026, 3, 20), -2500L, "BPAY 222222");

        CommitmentMatch match = Commitments.match(List.of(bill), List.of(),
            List.of(first, second),
            List.of(new CommitmentPin("id-1", "bill"), new CommitmentPin("id-2", "bill")),
            List.of(new CommitmentSettle("bill", LocalDate.of(2026, 3, 10), 7),
                new CommitmentSettle("bill", LocalDate.of(2026, 4, 10), 8),
                new CommitmentSettle("bill", LocalDate.of(2026, 5, 10), 9),
                new CommitmentSettle("bill", LocalDate.of(2026, 6, 10), 10)),
            Instant.parse("2026-04-05T00:00:00Z"));

        List<CommitmentOccurrence> off = match.occurrences().stream()
            .filter(CommitmentOccurrence::offSchedule).toList();
        assertEquals(1, off.size(), "two facts on one date are one row: " + match.occurrences());
        assertEquals(LocalDate.of(2026, 3, 20), off.getFirst().dueDate());
        assertEquals(-12500L, off.getFirst().amount().longValue(), "the day's total, nothing dropped");
        assertEquals("id-1", off.getFirst().matchedExternalId(), "the earliest fact is kept");
        assertEquals("pin", off.getFirst().matchedBy());
        assertOneRowPerDueDate(match);
    }

    @Test
    void aLeftoverOnACoveredOccurrenceDateMergesIntoThatRow() {
        Commitment bill = declared("bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 3, 10), -10000L, 1);
        CurrentFact covered = fact(1, "cba-netsaver", LocalDate.of(2026, 3, 10), -10000L, "BILL PAYMENT");
        CurrentFact extra = fact(2, "cba-netsaver", LocalDate.of(2026, 3, 10), -5000L, "BPAY 999888");

        CommitmentMatch match = Commitments.match(List.of(bill), List.of(rule("bill", "BILL|BPAY")),
            List.of(covered, extra), List.of(),
            List.of(new CommitmentSettle("bill", LocalDate.of(2026, 4, 10), 7),
                new CommitmentSettle("bill", LocalDate.of(2026, 5, 10), 8),
                new CommitmentSettle("bill", LocalDate.of(2026, 6, 10), 9)),
            Instant.parse("2026-04-05T00:00:00Z"));

        CommitmentOccurrence march = at(match, "bill", LocalDate.of(2026, 3, 10));
        assertEquals(OccurrenceStatus.OCCURRED, march.status(), "the covered row keeps its status");
        assertEquals(-15000L, march.amount().longValue(), "the extra merged into the covered day");
        assertEquals("id-1", march.matchedExternalId(), "the covered row keeps its fact");
        assertFalse(march.offSchedule());
        assertEquals(1, match.occurrences().stream()
            .filter(o -> o.dueDate().equals(LocalDate.of(2026, 3, 10))).count());
        assertOneRowPerDueDate(match);
    }

    /** The occurrence table's primary key: one row per (commitment, due date). */
    private static void assertOneRowPerDueDate(CommitmentMatch match) {
        long distinct = match.occurrences().stream()
            .map(o -> o.commitmentId() + "|" + o.dueDate()).distinct().count();
        assertEquals(match.occurrences().size(), distinct,
            "duplicate (commitment, dueDate) rows: " + match.occurrences());
    }

    @Test
    void aFactBeforeTheMaterialisedSpanIsNotAssigned() {
        Commitment bill = declared("bill", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 1, 15), -10000L, 1);
        CurrentFact old = fact(1, "bw-credit-card", LocalDate.of(2025, 12, 20), -10000L,
            "ACME BILL");

        CommitmentMatch match = run(List.of(bill), List.of(rule("bill", "ACME")), List.of(old),
            Instant.parse("2026-04-05T00:00:00Z"));

        CommitmentOccurrence january = at(match, "bill", LocalDate.of(2026, 1, 15));
        assertEquals(OccurrenceStatus.MISSED, january.status(),
            "history outside the materialised span does not satisfy a recent occurrence");
        assertNull(january.matchedExternalId());
        assertTrue(match.occurrences().stream()
            .noneMatch(o -> "id-1".equals(o.matchedExternalId())), "the old fact is not placed");
        assertEquals(3, arrears(match, "bill").count(), "January, February and March");
    }

    @Test
    void aVariableCommitmentKeepsOneFactPerOccurrence() {
        Commitment aws = declared("aws", Commitment.OUT, Cadence.MONTHLY,
            LocalDate.of(2026, 3, 5), -5000L, AmountKind.VARIABLE, 1);
        CurrentFact doubleCharge = fact(1, "ing-credit-card", LocalDate.of(2026, 3, 20), -10000L,
            "AMAZON WEB SERVICES");

        CommitmentMatch match = run(List.of(aws), List.of(rule("aws", "AMAZON WEB SERVICES")),
            List.of(doubleCharge), Instant.parse("2026-04-05T00:00:00Z"));

        CommitmentOccurrence march = at(match, "aws", LocalDate.of(2026, 3, 5));
        assertEquals(OccurrenceStatus.OCCURRED, march.status());
        assertEquals(-10000L, march.amount().longValue(),
            "a usage range cannot infer multiples: the whole fact, one occurrence");
        assertEquals("id-1", march.matchedExternalId());
        assertEquals(OccurrenceStatus.DUE, at(match, "aws", LocalDate.of(2026, 4, 5)).status(),
            "nothing is split across or pre-paid to the next period");
        assertEquals(0, arrears(match, "aws").count());
    }

    // ---- input validation ---------------------------------------------------------------------

    @Test
    void aRegularCommitmentWithoutAnAnchorIsRejected() {
        Commitment broken = new Commitment("broken", null, "broken", CommitmentOrigin.DECLARED,
            Commitment.OUT, Cadence.MONTHLY, AmountKind.FIXED, CommitmentKind.BILL,
            CommitmentStatus.ACTIVE, null, null, null, -1000L, null, null, null, List.of(), null,
            null, 0, 1.0, false, 0, null, 1L, null, null);
        assertThrows(IllegalArgumentException.class,
            () -> run(List.of(broken), List.of(), List.of(), ASOF));
    }
}
