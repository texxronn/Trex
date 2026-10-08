package trex.v2.core;

import org.junit.jupiter.api.Test;
import trex.v2.core.derive.AmountKind;
import trex.v2.core.derive.Cadence;
import trex.v2.core.derive.CategoryOrigin;
import trex.v2.core.derive.Commitment;
import trex.v2.core.derive.CommitmentKind;
import trex.v2.core.derive.CommitmentOrigin;
import trex.v2.core.derive.CommitmentStatus;
import trex.v2.core.derive.Commitments;
import trex.v2.core.derive.CurrentFact;
import trex.v2.core.derive.LegState;
import trex.v2.core.derive.OccurrenceStatus;
import trex.v2.core.derive.Role;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 1 acceptance for the commitment detector (V2-COMMITMENTS-PLAN.md §2.3, §2.4, §6):
 * detection, cadence, price steps, refunds and coverage over hand-built facts — no I/O, no clock.
 * The measured fixture shapes (Netflix, Stan, YouTube Premium, OpenAI's FCY rows) are reproduced
 * from the plan's worked acceptance (§7.1, §7.7) rather than read from the index: core tests
 * construct their facts.
 */
class CommitmentsTest {

    private static final Instant ASOF = Instant.parse("2026-10-01T00:00:00Z");

    // ---- fixtures ---------------------------------------------------------------------------

    private static CurrentFact row(long n, String account, LocalDate date, long amount, String raw) {
        return row(n, account, date, amount, raw, Role.TRANSACTION, LegState.EXTERNAL);
    }

    private static CurrentFact row(long n, String account, LocalDate date, long amount, String raw,
                                   Role role, LegState leg) {
        Fact fact = new Fact(n, "id-" + n, account, date, amount, 0, raw, null, 0,
            Observation.POSTED, "test", Provenance.BANK, null, "test/1",
            Instant.parse("2026-09-30T00:00:00Z"));
        return new CurrentFact(fact, role, null, leg, leg == LegState.MATCHED ? "TRF-" + n : null,
            null, CategoryOrigin.NONE, null, null);
    }

    private static Commitment candidate(List<Commitment> candidates, String key) {
        return candidates.stream().filter(c -> key.equals(c.candidateKey())).findFirst()
            .orElseThrow(() -> new AssertionError("no candidate for " + key + ": " + candidates));
    }

    // ---- detection --------------------------------------------------------------------------

    @Test
    void netflixMonthlyStepIsDetectedWithoutAFalseSameDayStep() {
        List<CurrentFact> facts = new ArrayList<>();
        long n = 1;
        LocalDate date = LocalDate.of(2024, 8, 15);
        for (int i = 0; i < 13; i++) {
            if (i == 12) {
                // One month split across two rows on the same day: the pair nets to the same 7.99
                // and must not invent a step.
                facts.add(row(n++, "bw-credit-card", date, -400, "PAYPAL *NETFLIX AUS"));
                facts.add(row(n++, "bw-credit-card", date, -399, "PAYPAL *NETFLIX AUS"));
            } else {
                facts.add(row(n++, "bw-credit-card", date, -799, "PAYPAL *NETFLIX AUS"));
            }
            date = date.plusMonths(1);
        }
        for (int i = 0; i < 13; i++) {
            facts.add(row(n++, "bw-credit-card", date, -999, "PAYPAL *NETFLIX AUS"));
            date = date.plusMonths(1);
        }

        List<Commitment> candidates = Commitments.detect(facts, ASOF);
        assertEquals(1, candidates.size(), candidates.toString());
        Commitment netflix = candidates.getFirst();
        assertEquals("PAYPAL *NETFLIX AUS", netflix.candidateKey());
        assertEquals(CommitmentOrigin.DETECTED, netflix.origin());
        assertEquals(Commitment.OUT, netflix.direction());
        assertEquals(Cadence.MONTHLY, netflix.cadence());
        assertEquals(AmountKind.FIXED, netflix.amountKind());
        assertEquals(CommitmentStatus.ACTIVE, netflix.status());
        assertEquals(26, netflix.occurrenceCount());
        assertEquals(1.0, netflix.regularity(), 0.0);
        assertEquals(2, netflix.steps().size(), "one opening point and exactly one step");
        assertEquals(-799L, netflix.previousAmount().longValue());
        assertEquals(-999L, netflix.currentAmount().longValue());
        assertEquals(LocalDate.of(2025, 9, 15), netflix.changeDate());
        assertEquals(25.03, netflix.changePct(), 0.01);
        assertEquals(-23374L, netflix.costToDate().longValue());
        assertEquals(-11988L, netflix.annualised().longValue());
        assertFalse(netflix.variable());
        assertEquals(LocalDate.of(2025, 9, 15), netflix.steps().get(1).date());
    }

    @Test
    void consecutivePriceStepsAreDetected() {
        List<CurrentFact> facts = new ArrayList<>();
        long n = 1;
        LocalDate date = LocalDate.of(2025, 1, 20);
        for (int i = 0; i < 3; i++) {
            facts.add(row(n++, "ing-credit-card", date, -2000, "PAYPAL *STAN"));
            date = date.plusMonths(1);
        }
        for (int i = 0; i < 3; i++) {
            facts.add(row(n++, "ing-credit-card", date, -4200, "PAYPAL *STAN"));
            date = date.plusMonths(1);
        }
        for (int i = 0; i < 3; i++) {
            facts.add(row(n++, "ing-credit-card", date, -4399, "PAYPAL *STAN"));
            date = date.plusMonths(1);
        }

        List<Commitment> candidates = Commitments.detect(facts, ASOF);
        assertEquals(1, candidates.size(), candidates.toString());
        Commitment stan = candidates.getFirst();
        assertEquals(3, stan.steps().size(), "$20 → $42 → $43.99");
        assertEquals(110.0, stan.steps().get(1).changePct(), 0.001);
        assertEquals(4.738, stan.changePct(), 0.001);
        assertEquals(-4200L, stan.previousAmount().longValue());
        assertEquals(-4399L, stan.currentAmount().longValue());
        assertEquals(LocalDate.of(2025, 7, 20), stan.changeDate());
        assertEquals(-31797L, stan.costToDate().longValue());
        assertEquals(-52788L, stan.annualised().longValue());
        assertFalse(stan.variable());
    }

    @Test
    void sameDayPairIsOneOccurrence() {
        List<CurrentFact> facts = List.of(
            row(1, "ing-credit-card", LocalDate.of(2025, 7, 21), -2000, "PAYPAL *STAN"),
            row(2, "ing-credit-card", LocalDate.of(2025, 8, 21), -2200, "PAYPAL *STAN"),
            row(3, "ing-credit-card", LocalDate.of(2025, 8, 21), -2000, "PAYPAL *STAN"),
            row(4, "ing-credit-card", LocalDate.of(2025, 9, 21), -4200, "PAYPAL *STAN"));

        List<Commitment> candidates = Commitments.detect(facts, ASOF);
        assertEquals(1, candidates.size(), candidates.toString());
        Commitment stan = candidates.getFirst();
        assertEquals(3, stan.occurrenceCount(), "$22 + $20 is one occurrence");
        assertEquals(-4200L, stan.currentAmount().longValue());
        assertEquals(-10400L, stan.costToDate().longValue());
        assertEquals(2, stan.steps().size());
        assertEquals(-4200L, stan.steps().get(1).amount());
        assertEquals(-2000L, stan.steps().get(1).previousAmount().longValue());
    }

    @Test
    void foreignCurrencyPriceIsUsedWhenTheWholeSeriesCarriesIt() {
        String openai = "OPENAI - Visa Purchase - Receipt 155070Foreign Currency Amount: USD 22In OPENAI.COM Date 26 Sep 2026";
        List<CurrentFact> facts = List.of(
            row(1, "ing-credit-card", LocalDate.of(2025, 1, 27), -3142, openai),
            row(2, "ing-credit-card", LocalDate.of(2025, 2, 27), -3310, openai),
            row(3, "ing-credit-card", LocalDate.of(2025, 3, 27), -3200, openai));

        List<Commitment> candidates = Commitments.detect(facts, ASOF);
        assertEquals(1, candidates.size(), candidates.toString());
        Commitment commitment = candidates.getFirst();
        assertEquals("OPENAI", commitment.candidateKey());
        assertEquals(-2200L, commitment.currentAmount().longValue(), "the FCY price, not the AUD charge");
        assertEquals(1, commitment.steps().size(), "AUD wobble is not a step");
        assertFalse(commitment.variable());
        assertEquals(-6600L, commitment.costToDate().longValue());
    }

    @Test
    void audIsUsedWhenOnlySomeRowsCarryAForeignAmount() {
        String plain = "OPENAI - Visa Purchase - Receipt 155070 In OPENAI.COM";
        String foreign = "OPENAI - Visa Purchase - Receipt 155070Foreign Currency Amount: USD 22In OPENAI.COM";
        List<CurrentFact> facts = List.of(
            row(1, "ing-credit-card", LocalDate.of(2025, 1, 27), -1000, plain),
            row(2, "ing-credit-card", LocalDate.of(2025, 2, 27), -1000, plain),
            row(3, "ing-credit-card", LocalDate.of(2025, 3, 27), -1000, foreign));

        List<Commitment> candidates = Commitments.detect(facts, ASOF);
        assertEquals(1, candidates.size(), candidates.toString());
        Commitment commitment = candidates.getFirst();
        assertEquals(-1000L, commitment.currentAmount().longValue(),
            "a mixed series stays on one basis — AUD — or FX would read as a step");
        assertEquals(1, commitment.steps().size());
    }

    @Test
    void endedSeriesClassifiesEndedAgainstTheFrontier() {
        List<CurrentFact> facts = new ArrayList<>();
        long n = 1;
        LocalDate date = LocalDate.of(2024, 1, 29);
        for (int i = 0; i < 8; i++) {
            facts.add(row(n++, "ing-credit-card", date, -349,
                "GOOGLE *YouTubePremium   650-253-0000 CA189.00 INR"));
            date = date.plusMonths(1);
        }
        // The account keeps posting for two more years; the series went silent in 2024.
        facts.add(row(n++, "ing-credit-card", LocalDate.of(2026, 9, 15), -1000, "COLES 1234"));

        List<Commitment> candidates = Commitments.detect(facts, ASOF);
        assertEquals(1, candidates.size(), candidates.toString());
        Commitment youtube = candidates.getFirst();
        assertEquals("GOOGLE *YOUTUBEPREMIUM", youtube.candidateKey());
        assertEquals(Cadence.MONTHLY, youtube.cadence());
        assertEquals(CommitmentStatus.ENDED, youtube.status());
        assertEquals(LocalDate.of(2024, 1, 29), youtube.firstDate());
        assertEquals(LocalDate.of(2024, 8, 29), youtube.lastDate());
        assertEquals(213, youtube.spanDays());
    }

    @Test
    void coverageStatusIsRelativeToTheAccountFrontier() {
        List<CurrentFact> facts = new ArrayList<>();
        long n = 1;
        for (String account : List.of("active-account", "dormant-account", "ended-account")) {
            LocalDate date = LocalDate.of(2025, 10, 15);
            for (int i = 0; i < 3; i++) {
                facts.add(row(n++, account, date, -500, "MERCHANT " + account.toUpperCase()));
                date = date.plusMonths(1);
            }
        }
        // One cadence is 30 days (tolerance 6): active ≤ 36 silent, ended > 60.
        facts.add(row(n++, "active-account", LocalDate.of(2026, 1, 15), -100, "FRONTIER ONE"));
        facts.add(row(n++, "dormant-account", LocalDate.of(2026, 1, 25), -100, "FRONTIER TWO"));
        facts.add(row(n++, "ended-account", LocalDate.of(2026, 2, 20), -100, "FRONTIER THREE"));

        List<Commitment> candidates = Commitments.detect(facts, ASOF);
        assertEquals(3, candidates.size(), candidates.toString());
        assertEquals(CommitmentStatus.ACTIVE, candidate(candidates, "MERCHANT ACTIVE-ACCOUNT").status());
        assertEquals(CommitmentStatus.DORMANT, candidate(candidates, "MERCHANT DORMANT-ACCOUNT").status());
        assertEquals(CommitmentStatus.ENDED, candidate(candidates, "MERCHANT ENDED-ACCOUNT").status());
    }

    @Test
    void identicalInputsGiveIdenticalCandidates() {
        List<CurrentFact> facts = new ArrayList<>();
        long n = 1;
        LocalDate date = LocalDate.of(2026, 1, 3);
        for (int i = 0; i < 4; i++) {
            facts.add(row(n++, "bw-credit-card", date, -999, "PAYPAL *NETFLIX AUS"));
            date = date.plusMonths(1);
        }
        date = LocalDate.of(2026, 7, 1);
        for (int i = 0; i < 6; i++) {
            facts.add(row(n++, "ing-salary", date, 150000, "PAYROLL SPLIT"));
            date = date.plusDays(7);
        }

        List<Commitment> first = Commitments.detect(facts, ASOF);
        List<Commitment> second = Commitments.detect(facts, ASOF);
        assertEquals(first, second);
        assertEquals(first.stream().map(Commitment::stateHash).toList(),
            second.stream().map(Commitment::stateHash).toList());
    }

    @Test
    void matchedTransferLegsAreInScope() {
        List<CurrentFact> facts = new ArrayList<>();
        long n = 1;
        LocalDate date = LocalDate.of(2026, 7, 5);
        for (int i = 0; i < 3; i++) {
            facts.add(row(n++, "cba-smartaccess", date, -250000, "HOME LOAN REPAYMENT",
                Role.TRANSACTION, LegState.MATCHED));
            date = date.plusMonths(1);
        }

        List<Commitment> candidates = Commitments.detect(facts, ASOF);
        assertEquals(1, candidates.size(), candidates.toString());
        Commitment loan = candidates.getFirst();
        assertEquals(Cadence.MONTHLY, loan.cadence());
        assertEquals(Commitment.OUT, loan.direction());
        assertEquals(-250000L, loan.currentAmount().longValue());
        assertEquals(-3000000L, loan.annualised().longValue());
    }

    @Test
    void noopFactsAreExcluded() {
        List<CurrentFact> noops = List.of(
            row(1, "cash-ron", LocalDate.of(2026, 6, 10), -5000, "GYM MEMBERSHIP", Role.NOOP, LegState.EXTERNAL),
            row(2, "cash-ron", LocalDate.of(2026, 7, 10), -5000, "GYM MEMBERSHIP", Role.NOOP, LegState.EXTERNAL),
            row(3, "cash-ron", LocalDate.of(2026, 8, 10), -5000, "GYM MEMBERSHIP", Role.NOOP, LegState.EXTERNAL),
            row(4, "cash-ron", LocalDate.of(2026, 9, 10), -5000, "GYM MEMBERSHIP", Role.NOOP, LegState.EXTERNAL));
        assertTrue(Commitments.detect(noops, ASOF).isEmpty());

        List<CurrentFact> mixed = new ArrayList<>();
        long n = 1;
        LocalDate date = LocalDate.of(2026, 6, 15);
        for (int i = 0; i < 3; i++) {
            mixed.add(row(n++, "cba-netsaver", date, -32000, "COUNCIL RATES"));
            date = date.plusMonths(1);
        }
        mixed.add(row(n++, "cba-netsaver", LocalDate.of(2026, 6, 20), -32000, "COUNCIL RATES",
            Role.NOOP, LegState.EXTERNAL));
        mixed.add(row(n++, "cba-netsaver", LocalDate.of(2026, 7, 20), -32000, "COUNCIL RATES",
            Role.NOOP, LegState.EXTERNAL));

        List<Commitment> candidates = Commitments.detect(mixed, ASOF);
        assertEquals(1, candidates.size(), candidates.toString());
        assertEquals(3, candidates.getFirst().occurrenceCount(),
            "the noop rows are not occurrences of the series");
    }

    @Test
    void refundsAreNettedAgainstTheChargeTheyReverse() {
        List<CurrentFact> facts = List.of(
            row(1, "ing-credit-card", LocalDate.of(2026, 1, 2), -999, "PAYPAL *STAN"),
            row(2, "ing-credit-card", LocalDate.of(2026, 2, 2), -999, "PAYPAL *STAN"),
            row(3, "ing-credit-card", LocalDate.of(2026, 3, 2), -999, "PAYPAL *STAN"),
            row(4, "ing-credit-card", LocalDate.of(2026, 4, 2), -999, "PAYPAL *STAN"),
            row(5, "ing-credit-card", LocalDate.of(2026, 4, 10), 999, "PAYPAL *STAN"));

        List<Commitment> candidates = Commitments.detect(facts, ASOF);
        assertEquals(1, candidates.size(), candidates.toString());
        Commitment stan = candidates.getFirst();
        assertEquals(3, stan.occurrenceCount(), "the refunded April charge is gone, not a fourth point");
        assertEquals(-2997L, stan.costToDate().longValue());
        assertEquals(-999L, stan.currentAmount().longValue());
        assertEquals(1, stan.steps().size(), "a refund is not a price step");
        assertEquals(Commitment.OUT, stan.direction());
    }

    @Test
    void gapsThatFitNoBucketAreNotACandidate() {
        List<CurrentFact> facts = List.of(
            row(1, "ing-orange", LocalDate.of(2026, 1, 1), -1000, "ODD MERCHANT"),
            row(2, "ing-orange", LocalDate.of(2026, 1, 23), -1000, "ODD MERCHANT"),
            row(3, "ing-orange", LocalDate.of(2026, 2, 14), -1000, "ODD MERCHANT"),
            row(4, "ing-orange", LocalDate.of(2026, 3, 8), -1000, "ODD MERCHANT"));

        assertTrue(Commitments.detect(facts, ASOF).isEmpty(), "22-day gaps fit no bucket");
    }

    @Test
    void lowRegularityIsNotACandidate() {
        // Gaps 30, 3, 45, 30, 90: the median classifies monthly but only two gaps fit the bucket.
        List<CurrentFact> facts = List.of(
            row(1, "ing-orange", LocalDate.of(2026, 1, 1), -1000, "LOOSE MERCHANT"),
            row(2, "ing-orange", LocalDate.of(2026, 1, 31), -1000, "LOOSE MERCHANT"),
            row(3, "ing-orange", LocalDate.of(2026, 2, 3), -1000, "LOOSE MERCHANT"),
            row(4, "ing-orange", LocalDate.of(2026, 3, 20), -1000, "LOOSE MERCHANT"),
            row(5, "ing-orange", LocalDate.of(2026, 4, 19), -1000, "LOOSE MERCHANT"),
            row(6, "ing-orange", LocalDate.of(2026, 7, 18), -1000, "LOOSE MERCHANT"));

        assertTrue(Commitments.detect(facts, ASOF).isEmpty(), "regularity 2/5 is below 0.7");
    }

    @Test
    void detectionCountIsPinnedOnASyntheticUniverse() {
        List<CurrentFact> facts = new ArrayList<>();
        long n = 1;

        // Five predictable series that pass: three monthly, one weekly income, one annual.
        for (String key : List.of("MONTHLY ONE", "MONTHLY TWO", "MONTHLY THREE")) {
            LocalDate date = LocalDate.of(2026, 1, 10);
            for (int i = 0; i < 4; i++) {
                facts.add(row(n++, "bw-credit-card", date, -1000, key));
                date = date.plusMonths(1);
            }
        }
        LocalDate date = LocalDate.of(2026, 7, 1);
        for (int i = 0; i < 6; i++) {
            facts.add(row(n++, "ing-salary", date, 1000, "PAYROLL SPLIT"));
            date = date.plusDays(7);
        }
        date = LocalDate.of(2024, 3, 1);
        for (int i = 0; i < 3; i++) {
            facts.add(row(n++, "bw-credit-card", date, -20000, "OA ANNUAL FEE"));
            date = date.plusYears(1);
        }

        // Four shapes that must not pass: too few, noops only, no bucket, low regularity.
        facts.add(row(n++, "bw-credit-card", LocalDate.of(2026, 2, 1), -1000, "TWO ONLY"));
        facts.add(row(n++, "bw-credit-card", LocalDate.of(2026, 3, 1), -1000, "TWO ONLY"));
        date = LocalDate.of(2026, 1, 5);
        for (int i = 0; i < 4; i++) {
            facts.add(row(n++, "cash-ron", date, -1000, "NOOP ONLY", Role.NOOP, LegState.EXTERNAL));
            date = date.plusMonths(1);
        }
        date = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 4; i++) {
            facts.add(row(n++, "bw-credit-card", date, -1000, "ODD GAPS"));
            date = date.plusDays(22);
        }
        for (LocalDate gap : List.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 3), LocalDate.of(2026, 3, 20), LocalDate.of(2026, 4, 19),
                LocalDate.of(2026, 7, 18))) {
            facts.add(row(n++, "bw-credit-card", gap, -1000, "LOOSE"));
        }

        List<Commitment> candidates = Commitments.detect(facts, ASOF);
        assertEquals(5, candidates.size(), candidates.toString());
        assertEquals(List.of("MONTHLY ONE", "MONTHLY THREE", "MONTHLY TWO", "OA ANNUAL FEE", "PAYROLL SPLIT"),
            candidates.stream().map(Commitment::candidateKey).sorted().toList());
        assertEquals(Commitment.IN, candidate(candidates, "PAYROLL SPLIT").direction());
        assertEquals(Cadence.WEEKLY, candidate(candidates, "PAYROLL SPLIT").cadence());
        assertEquals(Cadence.ANNUAL, candidate(candidates, "OA ANNUAL FEE").cadence());
        assertEquals(52000L, candidate(candidates, "PAYROLL SPLIT").annualised().longValue());
    }

    @Test
    void candidateIdAndStateHashFollowTheRowContract() {
        List<CurrentFact> facts = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 1, 3);
        for (int i = 0; i < 3; i++) {
            facts.add(row(i + 1, "bw-credit-card", date, -999, "PAYPAL *NETFLIX AUS"));
            date = date.plusMonths(1);
        }

        Commitment commitment = Commitments.detect(facts, ASOF).getFirst();
        assertTrue(commitment.commitmentId().startsWith("cand|"));
        assertEquals("cand|".length() + 16, commitment.commitmentId().length());
        assertEquals("sha256:".length() + 64, commitment.stateHash().length());
        assertEquals(CommitmentKind.OTHER, commitment.kind(), "detection does not guess a kind");
    }

    @Test
    void enumValuesAreThePlanValues() {
        assertEquals(List.of("weekly", "fortnightly", "monthly", "bimonthly", "quarterly",
                "semiannual", "annual", "irregular"),
            Arrays.stream(Cadence.values()).map(Cadence::wire).toList());
        assertEquals(List.of("fixed", "variable", "range"),
            Arrays.stream(AmountKind.values()).map(AmountKind::wire).toList());
        assertEquals(List.of("subscription", "bill", "insurance", "fee", "tax", "income", "other"),
            Arrays.stream(CommitmentKind.values()).map(CommitmentKind::wire).toList());
        assertEquals(List.of("detected", "declared"),
            Arrays.stream(CommitmentOrigin.values()).map(CommitmentOrigin::wire).toList());
        assertEquals(List.of("candidate", "active", "dormant", "ended"),
            Arrays.stream(CommitmentStatus.values()).map(CommitmentStatus::wire).toList());
        assertEquals(List.of("occurred", "settled", "due", "partial", "missed"),
            Arrays.stream(OccurrenceStatus.values()).map(OccurrenceStatus::wire).toList());
        assertEquals("out", Commitment.OUT);
        assertEquals("in", Commitment.IN);
    }
}
