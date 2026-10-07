package trex.v2.core;

import org.junit.jupiter.api.Test;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Profiles;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.TransferRules;
import trex.v2.core.config.TransferRules.TransferPattern;
import trex.v2.core.config.User;
import trex.v2.core.derive.Confidence;
import trex.v2.core.derive.CurrentFact;
import trex.v2.core.derive.Derivation;
import trex.v2.core.derive.Derive;
import trex.v2.core.derive.LegState;
import trex.v2.core.derive.Opening;
import trex.v2.core.derive.Reconciliation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §6.10: a clearing account pairs directly, holds no facts, and opens at closing − Σ movements. */
class ClearingTest {

    private static final Instant ASOF = Instant.parse("2026-10-01T00:00:00Z");

    private static DeriveConfig config() {
        Registry registry = new Registry(
            Map.of("ing-orange", new Account("ing-orange", "AUD", BalanceSource.STATEMENT, 7),
                "nab-fixed", new Account("nab-fixed", "AUD", BalanceSource.CLEARING, 7, 0L, null)),
            Map.of("ron", new User("ron", "Ron", true, "weekly")));
        TransferRules rules = new TransferRules(4, 0, 0, 30, 0.5, Map.of(
            "ing-orange", List.of(new TransferPattern("NAB Fixed Payments",
                Pattern.compile("NAB Fixed Payments", Pattern.CASE_INSENSITIVE), Rail.BANK_TRANSFER, true,
                "nab-fixed"))));
        return new DeriveConfig(registry,
            RuleSet.compile("t.yaml", new RuleSet.File(List.of("FOO"), List.of())),
            rules, Profiles.empty(), "sha256:cfg");
    }

    private static Fact fact(long n, String id, String account, long amount, String raw) {
        return new Fact(n, id, account, LocalDate.of(2026, 9, 1), amount, 0, raw, null, 0,
            Observation.POSTED, "test", Provenance.BANK, null, "test/1", ASOF);
    }

    @Test
    void aLegPairsDirectlyWithItsClearingAccount() {
        Derivation d = Derive.derive(List.of(
            fact(1, "a", "ing-orange", -3180000, "NAB Fixed Payments")), List.of(), config(), ASOF);
        assertEquals(1, d.transfers().size());
        assertEquals("nab-fixed", d.transfers().getFirst().clearingAccount());
        assertEquals("a", d.transfers().getFirst().fromLeg());
        assertEquals("nab-fixed", d.transfers().getFirst().toLeg());
        assertEquals(Rail.BANK_TRANSFER, d.transfers().getFirst().method());
        CurrentFact leg = d.current("a").orElseThrow();
        assertEquals(LegState.MATCHED, leg.leg());
        assertEquals(d.transfers().getFirst().transferId(), leg.transferId());
        // One real leg + an account side: exactly one TRANSFER unit, carried by the real leg.
        assertEquals(1, d.units().stream().filter(u -> u.unitKind().equals("TRANSFER")).count());
    }

    @Test
    void theComputedOpeningLandsOnTheDeclaredClosing() {
        Derivation d = Derive.derive(List.of(
            fact(1, "a", "ing-orange", -3180000, "NAB Fixed Payments")), List.of(), config(), ASOF);
        Reconciliation.AccountResult r = Reconciliation.clearing("nab-fixed", -3180000, 0L);
        assertEquals(Reconciliation.Status.CLEARING, r.status());
        assertTrue(r.balances(), "a clearing account is never wrong");

        Opening.PerAccount p = Opening.of(d.current().stream().map(CurrentFact::fact).toList(),
            config().registry(), d.transfers()).stream()
            .filter(x -> x.accountRef().equals("nab-fixed")).findFirst().orElseThrow();
        assertTrue(p.clearing());
        assertEquals(0, p.latestBalance());
        assertEquals(-3180000, p.backwardOpening());
    }

    @Test
    void aRefundReversesDirection() {
        Derivation d = Derive.derive(List.of(
            fact(1, "a", "ing-orange", 500, "NAB Fixed Payments")), List.of(), config(), ASOF);
        assertEquals("nab-fixed", d.transfers().getFirst().fromLeg());
        assertEquals("a", d.transfers().getFirst().toLeg());
        assertEquals(Confidence.EXACT, d.transfers().getFirst().confidence());
    }
}
