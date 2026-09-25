package trex.core.state;

import org.junit.jupiter.api.Test;
import trex.core.CanonicalEvent;
import trex.core.EventState;
import trex.core.Provenance;
import trex.core.TypeHint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC §7 test 31 — the third reconciliation state.
 * <p>
 * A declared account's chain never closes: only an attestation carries a balance, so the history
 * is islands with deliberate gaps. Calling that "broken" would make {@code ok} permanently false
 * and destroy the tripwire for every account that can actually be checked — which is the failure
 * this test exists to prevent. The last case is the one that matters most: the third state must
 * not become a way to hide a real break.
 */
class ReconciliationDeclaredTest {

    private static CanonicalEvent row(long n, String account, LocalDate date, long amount,
                                      long balance, TypeHint type) {
        return new CanonicalEvent(n, "e" + n, account, null, "AUD", date, amount, balance,
            "x", "x", type, null, null, null, EventState.EXTERNAL, null, List.of(),
            Provenance.BANK, "manual", null, null, null, null, null, null, Instant.EPOCH);
    }

    /** n is offset so a bank row never collides with a cash row's externalId in the same fixture. */
    private static CanonicalEvent bank(long n, long amount, long balance) {
        return row(10 + n, "ing-savings", LocalDate.of(2026, 9, (int) n), amount, balance,
            amount < 0 ? TypeHint.WITHDRAWAL : TypeHint.DEPOSIT);
    }

    /**
     * $160 attested, then $40 recorded, then $60 attested. The account went down $100 but only
     * $40 was written down, so $60 was spent and never itemised. That figure is the output.
     */
    @Test
    void aDeclaredAccountReportsItsGapInsteadOfFailing() {
        var lines = List.of(
            row(1, "cash-ron", LocalDate.of(2026, 9, 1), 0, 16000, TypeHint.ATTESTATION),
            row(2, "cash-ron", LocalDate.of(2026, 9, 12), -4000, 0, TypeHint.WITHDRAWAL),
            row(3, "cash-ron", LocalDate.of(2026, 9, 25), 0, 6000, TypeHint.ATTESTATION));

        Map<String, Reconciliation.Result> r =
            Reconciliation.reconcile(lines, Set.of("cash-ron"));
        Reconciliation.Result cash = r.get("cash-ron");

        assertEquals(Reconciliation.Status.DECLARED, cash.status());
        assertEquals(-6000, cash.gap(), "cash spent and never itemised");
        assertEquals(16000, cash.opening());
        assertEquals(6000, cash.closing());
        assertTrue(cash.balances(), "a declared account is never 'wrong', only incomplete");
    }

    /** With nothing unrecorded, the gap is zero — the arrangement is not lossy by construction. */
    @Test
    void aFullyRecordedDeclaredAccountHasNoGap() {
        var lines = List.of(
            row(1, "cash-ron", LocalDate.of(2026, 9, 1), 0, 16000, TypeHint.ATTESTATION),
            row(2, "cash-ron", LocalDate.of(2026, 9, 12), -10000, 0, TypeHint.WITHDRAWAL),
            row(3, "cash-ron", LocalDate.of(2026, 9, 25), 0, 6000, TypeHint.ATTESTATION));

        assertEquals(0, Reconciliation.reconcile(lines, Set.of("cash-ron")).get("cash-ron").gap());
    }

    /** A statement account is untouched by any of this. */
    @Test
    void aStatementAccountStillReconcilesExactly() {
        var lines = List.of(bank(1, -1000, 9000), bank(2, -500, 8500));

        Reconciliation.Result r =
            Reconciliation.reconcile(lines, Set.of("cash-ron")).get("ing-savings");
        assertEquals(Reconciliation.Status.RECONCILED, r.status());
        assertEquals(10000, r.opening());
        assertEquals(8500, r.closing());
        assertTrue(r.balances());
    }

    /**
     * The one that guards the guard. A cash account present and healthy must not stop a genuine
     * break on a statement account from surfacing.
     */
    @Test
    void aDeclaredAccountCannotMaskABrokenOne() {
        var lines = List.of(
            row(1, "cash-ron", LocalDate.of(2026, 9, 1), 0, 16000, TypeHint.ATTESTATION),
            bank(1, -1000, 9000),
            bank(3, -500, 4000));          // 8500 expected: the chain is broken

        Map<String, Reconciliation.Result> r = Reconciliation.reconcile(lines, Set.of("cash-ron"));
        assertEquals(Reconciliation.Status.DECLARED, r.get("cash-ron").status());
        assertEquals(Reconciliation.Status.BROKEN, r.get("ing-savings").status());
        assertTrue(r.get("cash-ron").balances(), "the cash account is fine");
        assertTrue(!r.get("ing-savings").balances(), "and the broken one still fails");
    }
}
