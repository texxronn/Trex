package trex.v2.core.derive;

import org.junit.jupiter.api.Test;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §15.10: reconciliation over derived state, including the DECLARED case. */
class ReconciliationTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    private static Fact fact(long n, String id, String account, long amount, long balance) {
        return new Fact(n, id, account, LocalDate.of(2026, 9, (int) n), amount, balance, "x", null, 0,
            Observation.POSTED, "test", Provenance.BANK, null, "test/1", AT);
    }

    @Test
    void aStatementChainReconciles() {
        // opening 1000, -100, +500 -> closing 1400; sum 400 == closing - opening.
        Map<String, Reconciliation.AccountResult> r = Reconciliation.reconcile(List.of(
            fact(1, "a", "ing-savings", -100, 900),
            fact(2, "b", "ing-savings", 500, 1400)), Set.of());
        Reconciliation.AccountResult result = r.get("ing-savings");
        assertEquals(Reconciliation.Status.RECONCILED, result.status());
        assertEquals(1000, result.opening());
        assertEquals(1400, result.closing());
        assertEquals(400, result.sum());
        assertTrue(result.balances());
    }

    @Test
    void aNoopRowIsExcludedFromTheChainAndNamed() {
        // The same closing chain, with a statement-snapshot noop row that would break it if it
        // counted. It is skipped and listed under its account (§6.9).
        Fact snapshot = new Fact(3, "fee", "ing-savings", LocalDate.of(2026, 9, 3), -29900, 0,
            "Orange Advantage annual fee", null, 0, Observation.POSTED, "test", Provenance.BANK,
            null, "test/1", AT);
        Map<String, Reconciliation.AccountResult> r = Reconciliation.reconcile(List.of(
                fact(1, "a", "ing-savings", -100, 900),
                fact(2, "b", "ing-savings", 500, 1400)),
            List.of(snapshot), Set.of());
        Reconciliation.AccountResult result = r.get("ing-savings");
        assertEquals(Reconciliation.Status.RECONCILED, result.status());
        assertEquals(1000, result.opening());
        assertEquals(1400, result.closing());
        assertEquals(List.of("fee"), result.exclusions());
    }

    @Test
    void anAllNoopAccountIsReportedReconciledWithItsExclusions() {
        Fact snapshot = new Fact(3, "fee", "ing-variable-rate", LocalDate.of(2026, 9, 3), -29900, 0,
            "Orange Advantage annual fee", null, 0, Observation.POSTED, "test", Provenance.BANK,
            null, "test/1", AT);
        Map<String, Reconciliation.AccountResult> r = Reconciliation.reconcile(
            List.of(), List.of(snapshot), Set.of());
        Reconciliation.AccountResult result = r.get("ing-variable-rate");
        assertEquals(Reconciliation.Status.RECONCILED, result.status());
        assertEquals(List.of("fee"), result.exclusions());
        assertTrue(result.balances());
    }

    @Test
    void aBrokenChainIsABrokenFault() {
        // The balances do not share one opening/closing pair.
        Map<String, Reconciliation.AccountResult> r = Reconciliation.reconcile(List.of(
            fact(1, "a", "ing-savings", -100, 900),
            fact(2, "b", "ing-savings", 500, 2000)), Set.of());
        assertEquals(Reconciliation.Status.BROKEN, r.get("ing-savings").status());
        assertFalse(r.get("ing-savings").balances());
    }

    @Test
    void aDeclaredAccountReportsItsGap() {
        // 1000 stated, 200 recorded spent, 700 stated: 100 moved and was never itemised.
        Map<String, Reconciliation.AccountResult> r = Reconciliation.reconcile(List.of(
            fact(1, "t0", "cash-ron", 0, 1000),
            fact(2, "p1", "cash-ron", -200, 0),
            fact(3, "t1", "cash-ron", 0, 700)), Set.of("cash-ron"));
        Reconciliation.AccountResult result = r.get("cash-ron");
        assertEquals(Reconciliation.Status.DECLARED, result.status());
        assertEquals(1000, result.opening());
        assertEquals(700, result.closing());
        assertEquals(-100, result.gap());
        assertTrue(result.balances(), "a declared account is never wrong, only incomplete");
    }
}
