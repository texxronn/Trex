package trex.v2.core.derive;

import org.junit.jupiter.api.Test;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.Registry;
import trex.v2.core.config.User;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** §15.21: openings are honest — backward derivation, order-independent, gap reported. */
class OpeningTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    private static Registry registry() {
        return new Registry(
            Map.of("ing-savings", new Account("ing-savings", "AUD", BalanceSource.STATEMENT, 7),
                "cash-ron", new Account("cash-ron", "AUD", BalanceSource.DECLARED, 7)),
            Map.of("ron", new User("ron", "Ron", true, "weekly")));
    }

    private static Fact fact(long n, String id, String account, LocalDate date, long amount, long balance) {
        return new Fact(n, id, account, date, amount, balance, "x", null, 0, Observation.POSTED, "t",
            Provenance.BANK, null, "t/1", AT);
    }

    @Test
    void backwardOpeningIsOrderIndependentAndReportsNoGapWhenTheChainIsIntact() {
        Fact f1 = fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -100, 900);
        Fact f2 = fact(2, "b", "ing-savings", LocalDate.of(2026, 9, 2), 500, 1400);
        Opening.PerAccount forward = Opening.of(List.of(f1, f2), registry()).getFirst();
        Opening.PerAccount reversed = Opening.of(List.of(f2, f1), registry()).getFirst();
        assertEquals(forward, reversed, "the derivation does not depend on input order");
        assertEquals(1000, forward.backwardOpening());
        assertEquals(1000, forward.forwardOpening());
        assertEquals(0, forward.gap());
    }

    @Test
    void aMissingTransactionShowsUpAsAConfidenceGap() {
        // The bank's running balance says 1300 where the recorded amounts imply 1400.
        Opening.PerAccount account = Opening.of(List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -100, 900),
            fact(2, "b", "ing-savings", LocalDate.of(2026, 9, 2), 500, 1300)), registry()).getFirst();
        assertEquals(900, account.backwardOpening());
        assertEquals(1000, account.forwardOpening());
        assertEquals(-100, account.gap(), "the journal is short by 100, reported not hidden");
    }

    @Test
    void aDeclaredAccountOpensOnItsFirstAttestation() {
        Opening.PerAccount account = Opening.of(List.of(
            fact(1, "t0", "cash-ron", LocalDate.of(2026, 9, 1), 0, 1000),
            fact(2, "p1", "cash-ron", LocalDate.of(2026, 9, 2), -200, 0),
            fact(3, "t1", "cash-ron", LocalDate.of(2026, 9, 3), 0, 700)), registry()).getFirst();
        assertEquals(true, account.declared());
        assertEquals(1000, account.backwardOpening());
        assertEquals(700, account.latestBalance());
        assertEquals(-100, account.gap());
    }
}
