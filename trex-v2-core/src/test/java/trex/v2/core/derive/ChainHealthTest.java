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

/** §6.9: a fork is a value two rows contest; nooping a side previews the recomputed chain. */
class ChainHealthTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    private static Fact fact(long n, String id, long amount, long balance) {
        return new Fact(n, id, "ing-savings", LocalDate.of(2026, 9, (int) n), amount, balance, "row " + id,
            null, 0, Observation.POSTED, "test", Provenance.BANK, null, "test/1", AT);
    }

    private static ChainHealth.Account of(List<Fact> facts) {
        return ChainHealth.of(facts, List.of(), Set.of()).get("ing-savings");
    }

    @Test
    void aCleanChainHasNoForks() {
        ChainHealth.Account a = of(List.of(fact(1, "a", -100, 900), fact(2, "b", 500, 1400)));
        assertEquals(Reconciliation.Status.RECONCILED, a.status());
        assertTrue(a.reconciled());
        assertTrue(a.forks().isEmpty());
    }

    @Test
    void twoRowsClaimingTheSamePreviousBalanceAreAFork() {
        // a and b both open from 1000: two claimants at the same previous balance (OPENING).
        ChainHealth.Account a = of(List.of(
            fact(1, "a", -100, 900),
            fact(2, "b", -50, 950),
            fact(3, "c", 500, 1450)));
        assertFalse(a.reconciled());
        assertEquals(1, a.forks().size());
        ChainHealth.Fork fork = a.forks().getFirst();
        assertEquals(1000, fork.value());
        assertEquals(ChainHealth.Side.OPENING, fork.side());
        assertEquals(List.of("a", "b"), fork.externalIds());
    }

    @Test
    void aDisjointChainIsBrokenWithNoForkToName() {
        ChainHealth.Account a = of(List.of(fact(1, "a", -100, 900), fact(2, "b", 500, 2000)));
        assertEquals(Reconciliation.Status.BROKEN, a.status());
        assertTrue(a.forks().isEmpty());
    }

    @Test
    void previewingASideRecomputesTheChain() {
        List<Fact> facts = List.of(
            fact(1, "a", -100, 900),
            fact(2, "b", 300, 1200),
            fact(3, "c", 50, 950));
        // b and c both open from 900; c is the removable side.
        assertEquals(List.of("b", "c"), of(facts).forks().getFirst().externalIds());

        ChainHealth.Preview noopC = ChainHealth.preview(facts, List.of(), Set.of(), "c");
        assertTrue(noopC.reconciled(), "nooping the extra claimant closes the chain");
        assertEquals(1000, noopC.opening());
        assertEquals(1200, noopC.closing());
        assertTrue(noopC.remainingForks().isEmpty());

        // a is load-bearing: with a gone, b and c still contest 900.
        ChainHealth.Preview noopA = ChainHealth.preview(facts, List.of(), Set.of(), "a");
        assertFalse(noopA.reconciled());
        assertEquals(List.of("b", "c"), noopA.remainingForks().getFirst().externalIds());
    }
}
