package trex.v2.hub;

import org.junit.jupiter.api.Test;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.Registry;
import trex.v2.core.config.TransferRules;
import trex.v2.core.config.User;
import trex.v2.hub.api.EyeballAnomaly;
import trex.v2.hub.api.EyeballResponse;
import trex.v2.hub.api.LedgerRow;
import trex.v2.hub.api.ReviewRow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2-PROPOSAL.md §10.3: the eyeball walk's nine checks and the day-by-day view, pure over the
 * inputs the hub has read. Each test names the check it pins.
 */
class EyeballTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);
    private static final String PERIOD = "2026-09";
    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    @Test
    void aStatementBalanceThatDoesNotChainIsReported() {
        List<Fact> facts = List.of(
            fact(1, "s1", "ing-savings", LocalDate.of(2026, 8, 1), -1000, 9000, "COLES 1234"),
            fact(2, "s2", "ing-savings", LocalDate.of(2026, 9, 2), -500, 8400, "WOOLWORTHS 99"));
        EyeballResponse walk = walk(facts, List.of(), List.of(), List.of(),
            registry(statement("ing-savings")));
        assertKind(walk, Eyeball.BALANCE_CHAIN_BREAK, "s2");
    }

    @Test
    void aStemNeverSeenBeforeThePeriodIsReportedOnce() {
        List<Fact> facts = List.of(
            fact(1, "c1", "ing-savings", LocalDate.of(2026, 8, 1), -1000, 0, "COLES 1234"),
            fact(2, "n1", "ing-savings", LocalDate.of(2026, 9, 5), -2000, 0, "NEWSHOP 99"),
            fact(3, "n2", "ing-savings", LocalDate.of(2026, 9, 6), -3000, 0, "NEWSHOP 99"));
        EyeballResponse walk = walk(facts, List.of(), List.of(), List.of(),
            registry(statement("ing-savings")));
        assertEquals(1, count(walk, Eyeball.NEW_MERCHANT_STEM), "once per stem, not once per row");
        assertTrue(walk.anomalies().stream().anyMatch(a -> a.kind().equals(Eyeball.NEW_MERCHANT_STEM)
            && a.detail().contains("NEWSHOP 99")));
        assertFalse(walk.anomalies().stream().anyMatch(a -> a.kind().equals(Eyeball.NEW_MERCHANT_STEM)
            && a.detail().contains("COLES")));
    }

    @Test
    void anAmountAboveTheNinetyNinthPercentileIsReported() {
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            facts.add(fact(100 + i, "r" + i, "ing-savings",
                LocalDate.of(2025, 3, 1).plusDays(i), -(100 + i), 0, "REGULAR SHOP"));
        }
        facts.add(fact(900, "big", "ing-savings", LocalDate.of(2026, 9, 10), -999_999, 0, "REGULAR SHOP"));
        EyeballResponse walk = walk(facts, List.of(), List.of(), List.of(),
            registry(statement("ing-savings")));
        assertKind(walk, Eyeball.AMOUNT_OUTLIER, "big");
    }

    @Test
    void twoSameAmountsWithinTheWindowLookDuplicated() {
        List<Fact> facts = List.of(
            fact(1, "d1", "ing-savings", LocalDate.of(2026, 9, 1), -450, 0, "COFFEE CART"),
            fact(2, "d2", "ing-savings", LocalDate.of(2026, 9, 3), -450, 0, "COFFEE CART"));
        EyeballResponse walk = walk(facts, List.of(), List.of(), List.of(),
            registry(statement("ing-savings")));
        assertKind(walk, Eyeball.DUPLICATE_LOOKING, "d2");
    }

    @Test
    void aMonthlyChargeMissingThisMonthIsReported() {
        List<Fact> facts = List.of(
            fact(1, "g1", "ing-savings", LocalDate.of(2026, 6, 10), -2000, 0, "GYM MEMBERSHIP"),
            fact(2, "g2", "ing-savings", LocalDate.of(2026, 7, 10), -2100, 0, "GYM MEMBERSHIP"),
            fact(3, "g3", "ing-savings", LocalDate.of(2026, 8, 10), -2200, 0, "GYM MEMBERSHIP"),
            fact(4, "o1", "ing-savings", LocalDate.of(2026, 9, 1), -100, 0, "ONE OFF SHOP"));
        EyeballResponse walk = walk(facts, List.of(), List.of(), List.of(),
            registry(statement("ing-savings")));
        assertTrue(walk.anomalies().stream().anyMatch(a -> a.kind().equals(Eyeball.RECURRING_MISSING)
            && a.detail().contains("GYM MEMBERSHIP")));
    }

    @Test
    void anUnmatchedLegPastTheHoldWindowComesFromTheReviewItem() {
        List<Fact> facts = List.of(
            fact(1, "leg1", "ing-savings", LocalDate.of(2026, 9, 5), -5000, 0, "Transfer to Savings 1111"));
        List<ReviewRow> review = List.of(new ReviewRow("leg1", "UNMATCHED_LEG", "held past 30d", 5000L, AT, "h", null));
        EyeballResponse walk = walk(facts, List.of(), review, List.of(), registry(statement("ing-savings")));
        assertKind(walk, Eyeball.UNMATCHED_LEG, "leg1");
    }

    @Test
    void aStalePendingObservationIsReported() {
        List<PendingView> pending = List.of(
            new PendingView("pend1", "ing-savings", LocalDate.of(2026, 9, 1), -4995, null, "STALE"));
        EyeballResponse walk = walk(List.of(), List.of(), List.of(), pending, registry(statement("ing-savings")));
        assertKind(walk, Eyeball.STALE_PENDING, "pend1");
    }

    @Test
    void uncategorizedRowsAndOpenItemsAreScopedToThePeriod() {
        List<LedgerRow> rows = List.of(
            ledger("u1", 1, "ing-savings", LocalDate.of(2026, 9, 4), -700, "UNCATEGORIZED"),
            ledger("ok", 2, "ing-savings", LocalDate.of(2026, 9, 4), -800, "GROCERIES"));
        List<Fact> facts = List.of(
            fact(1, "u1", "ing-savings", LocalDate.of(2026, 9, 4), -700, 0, "SOMETHING"),
            fact(2, "ok", "ing-savings", LocalDate.of(2026, 9, 4), -800, 0, "COLES 1234"),
            fact(3, "out", "ing-savings", LocalDate.of(2026, 8, 4), -900, 0, "OLD SHOP"));
        List<ReviewRow> review = List.of(
            new ReviewRow("u1", "POTENTIAL_DUP", "x", 1L, AT, "h", "COLES 1234"),
            new ReviewRow("out", "POTENTIAL_DUP", "y", 1L, AT, "h", null));
        EyeballResponse walk = walk(facts, rows, review, List.of(), registry(statement("ing-savings")));
        assertKind(walk, Eyeball.UNCATEGORIZED, "u1");
        assertEquals(List.of("u1"), walk.openItems().stream().map(ReviewRow::subject).toList(),
            "only the in-period review item is open here");
    }

    @Test
    void anAccountQuieterThanItsUsualCadenceIsReported() {
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            facts.add(fact(1 + i, "q" + i, "ing-orange",
                LocalDate.of(2026, 1, 1).plusMonths(i), -1000 - i, 0, "SALARY CREDIT"));
        }
        EyeballResponse walk = walk(facts, List.of(), List.of(), List.of(), registry(statement("ing-orange")));
        assertKind(walk, Eyeball.ACCOUNT_SILENT, "ing-orange");
    }

    @Test
    void theDayWalkGroupsByDayTotalsAndClosesPerAccount() {
        List<LedgerRow> rows = List.of(
            ledger("a", 1, "ing-savings", LocalDate.of(2026, 9, 1), -1000, 9000, "COLES 1234"),
            ledger("b", 2, "ing-orange", LocalDate.of(2026, 9, 1), -500, 4000, "COFFEE"),
            ledger("leg", 3, "ing-savings", LocalDate.of(2026, 9, 1), -2000, 7000,
                "Transfer to Savings 1111", "TRANSFER", "MATCHED"),
            ledger("c", 4, "ing-savings", LocalDate.of(2026, 9, 2), 3000, 12000, "REFUND"));
        EyeballResponse walk = walk(List.of(), rows, List.of(), List.of(),
            registry(statement("ing-savings"), statement("ing-orange")));
        assertEquals(2, walk.buckets().size());
        assertEquals(-1500, walk.buckets().get(0).total(), "transfer legs never double-count");
        assertEquals(Map.of("ing-savings", 7000L, "ing-orange", 4000L), walk.buckets().get(0).closingBalances());
        assertEquals(3000, walk.buckets().get(1).total());
    }

    @Test
    void theWalkBucketsByDayWeekOrMonth() {
        List<LedgerRow> rows = List.of(
            ledger("a", 1, "ing-savings", LocalDate.of(2026, 9, 1), -1000, 9000, "COLES 1234"),
            ledger("b", 2, "ing-savings", LocalDate.of(2026, 9, 3), -500, 8500, "COFFEE CART"),
            ledger("c", 3, "ing-savings", LocalDate.of(2026, 10, 1), -200, 8300, "WOOLWORTHS 99"));
        Registry reg = registry(statement("ing-savings"));
        TransferRules rules = TransferRules.defaults(List.of("Transfer"));
        assertEquals(3, Eyeball.walk("2026", "ron", AS_OF, "day", List.of(), rows, List.of(), List.of(), reg, rules)
            .buckets().size());
        assertEquals(2, Eyeball.walk("2026", "ron", AS_OF, "week", List.of(), rows, List.of(), List.of(), reg, rules)
            .buckets().size(), "two different ISO weeks");
        assertEquals(2, Eyeball.walk("2026", "ron", AS_OF, "month", List.of(), rows, List.of(), List.of(), reg, rules)
            .buckets().size(), "September and October");
    }

    @Test
    void anEmptyWalkIsEmptyNotBroken() {
        EyeballResponse walk = walk(List.of(), List.of(), List.of(), List.of(), registry(statement("ing-savings")));
        assertTrue(walk.anomalies().isEmpty());
        assertTrue(walk.buckets().isEmpty());
        assertTrue(walk.openItems().isEmpty());
        assertEquals(PERIOD, walk.period());
        assertEquals(AS_OF, walk.asOf());
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static EyeballResponse walk(List<Fact> facts, List<LedgerRow> rows, List<ReviewRow> review,
                                        List<PendingView> pending, Registry registry) {
        return Eyeball.walk(PERIOD, "ron", AS_OF, "day", facts, rows, review, pending, registry,
            TransferRules.defaults(List.of("Transfer")));
    }

    private static void assertKind(EyeballResponse walk, String kind, String subject) {
        assertTrue(walk.anomalies().stream().anyMatch(a -> a.kind().equals(kind) && subject.equals(a.subject())),
            "expected " + kind + " on " + subject + ", got " + walk.anomalies());
    }

    private static long count(EyeballResponse walk, String kind) {
        return walk.anomalies().stream().filter(a -> a.kind().equals(kind)).count();
    }

    private static Fact fact(long n, String id, String account, LocalDate date, long amount, long balance,
                             String raw) {
        return new Fact(n, id, account, date, amount, balance, raw, null, 0, Observation.POSTED,
            "ing-csv", Provenance.BANK, null, "ing-csv/1", AT);
    }

    private static LedgerRow ledger(String id, long n, String account, LocalDate date, long amount,
                                    String category) {
        return ledger(id, n, account, date, amount, 0, "row", category, "EXTERNAL");
    }

    private static LedgerRow ledger(String id, long n, String account, LocalDate date, long amount,
                                    long balance, String raw) {
        return ledger(id, n, account, date, amount, balance, raw, "EXTERNAL");
    }

    private static LedgerRow ledger(String id, long n, String account, LocalDate date, long amount,
                                    long balance, String raw, String category) {
        return ledger(id, n, account, date, amount, balance, raw, category, "EXTERNAL");
    }

    private static LedgerRow ledger(String id, long n, String account, LocalDate date, long amount,
                                    long balance, String raw, String category, String leg) {
        return new LedgerRow(id, n, account, date, amount, balance, raw, leg, "transaction", null, category,
            "RULE", null, false);
    }

    private static Account statement(String ref) {
        return new Account(ref, "AUD", BalanceSource.STATEMENT, 7);
    }

    private static Registry registry(Account... accounts) {
        Map<String, Account> map = new LinkedHashMap<>();
        for (Account a : accounts) {
            map.put(a.ref(), a);
        }
        return new Registry(map, Map.of("ron", new User("ron", "Ron", true, null)));
    }
}
