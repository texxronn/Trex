package trex.v2.core;

import org.junit.jupiter.api.Test;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.TransferRules;
import trex.v2.core.config.User;
import trex.v2.core.derive.Derivation;
import trex.v2.core.derive.Derive;
import trex.v2.core.derive.PendingRow;
import trex.v2.core.derive.PendingState;
import trex.v2.core.derive.ReviewItem;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §15.11: pending is never lost and never counted; settled by derivation or SETTLE, stale by window. */
class PendingTest {

    private static final Instant ASOF = Instant.parse("2026-10-01T00:00:00Z");

    private static DeriveConfig config() {
        return new DeriveConfig(
            new Registry(Map.of("ing-savings", new Account("ing-savings", "AUD", BalanceSource.STATEMENT, 7)),
                Map.of("ron", new User("ron", "Ron", true, "weekly"))),
            RuleSet.compile("t.yaml", new RuleSet.File(List.of("FUEL"), List.of())),
            TransferRules.defaults(List.of("Transfer")), trex.v2.core.config.Profiles.empty(), "sha256:cfg");
    }

    private static Fact fact(long n, String id, long amount, String raw, Observation observation, LocalDate date) {
        return new Fact(n, id, "ing-savings", date, amount, 0, raw, null, 0, observation, "bw-csv",
            Provenance.BANK, null, "bw-csv/1", ASOF);
    }

    private static PendingRow pending(Derivation d) {
        return d.pending().getFirst();
    }

    @Test
    void aUniquePlausibleRowSettlesThePendingObservation() {
        Derivation d = Derive.derive(List.of(
            fact(1, "p", -4995, "AUTHORISATION ONLY BP FUEL 1234", Observation.PENDING, LocalDate.of(2026, 9, 1)),
            fact(2, "q", -4995, "BP FUEL 1234", Observation.POSTED, LocalDate.of(2026, 9, 2))), List.of(), config(), ASOF);
        assertEquals(1, d.pending().size());
        assertEquals(PendingState.SETTLED, pending(d).state());
        assertEquals("q", pending(d).settledBy());
        assertTrue(d.current("p").isEmpty(), "a pending observation is never current");
    }

    @Test
    void twoPlausibleRowsAreAmbiguousAndClosedBySettle() {
        List<Fact> facts = List.of(
            fact(1, "p", -4995, "AUTHORISATION ONLY BP FUEL 1234", Observation.PENDING, LocalDate.of(2026, 9, 1)),
            fact(2, "q1", -4995, "BP FUEL 1234", Observation.POSTED, LocalDate.of(2026, 9, 2)),
            fact(3, "q2", -4995, "BP FUEL 5678", Observation.POSTED, LocalDate.of(2026, 9, 2)));
        Derivation ambiguous = Derive.derive(facts, List.of(), config(), ASOF);
        assertEquals(PendingState.OPEN, pending(ambiguous).state());
        assertTrue(ambiguous.review().stream()
            .anyMatch(r -> r.kind().equals(ReviewItem.AMBIGUOUS_SETTLEMENT)), ambiguous.review().toString());

        Derivation settled = Derive.derive(facts, List.of(
            new Decision.Settle(4, "p", "q2", "the second one", Actor.USER, "ron", ASOF)), config(), ASOF);
        assertEquals(PendingState.SETTLED, pending(settled).state());
        assertEquals("q2", pending(settled).settledBy());
    }

    @Test
    void aPendingWithNoCandidatePastTheWindowGoesStale() {
        Derivation d = Derive.derive(List.of(
            fact(1, "p", -4995, "AUTHORISATION ONLY BP FUEL 1234", Observation.PENDING, LocalDate.of(2026, 9, 1)),
            // A later statement arrived on this account, covering the pending date, without settling it.
            fact(2, "other", -2000, "SOMETHING ELSE", Observation.POSTED, LocalDate.of(2026, 9, 20))),
            List.of(), config(), ASOF);
        assertEquals(PendingState.STALE, pending(d).state());
        assertTrue(d.review().stream().anyMatch(r -> r.kind().equals(ReviewItem.STALE_PENDING)));
    }

    @Test
    void aPendingInsideTheWindowStaysOpen() {
        Derivation d = Derive.derive(List.of(
            fact(1, "p", -4995, "AUTHORISATION ONLY BP FUEL 1234", Observation.PENDING, LocalDate.of(2026, 9, 20)),
            fact(2, "other", -2000, "SOMETHING ELSE", Observation.POSTED, LocalDate.of(2026, 9, 21))),
            List.of(), config(), ASOF);
        assertEquals(PendingState.OPEN, pending(d).state());
        assertTrue(d.review().isEmpty());
    }
}
