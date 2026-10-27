package trex.v2.core;

import org.junit.jupiter.api.Test;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Profiles;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.TransferRules;
import trex.v2.core.config.User;
import trex.v2.core.derive.ClearingLeg;
import trex.v2.core.derive.ClosedAccount;
import trex.v2.core.derive.Confidence;
import trex.v2.core.derive.CurrentFact;
import trex.v2.core.derive.Derivation;
import trex.v2.core.derive.Derive;
import trex.v2.core.derive.IneffectiveDecision;
import trex.v2.core.derive.LegState;
import trex.v2.core.derive.Opening;
import trex.v2.core.derive.Reconciliation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2-CLOSE-ACCOUNT-PLAN.md §3.2: a statement account closed by a decision keeps its facts, lands its
 * derived closing on the declared value, and pairs the legs a person named as settling it.
 */
class CloseAccountTest {

    private static final Instant ASOF = Instant.parse("2026-10-27T00:00:00Z");
    private static final LocalDate CLOSED = LocalDate.of(2026, 10, 23);

    private static DeriveConfig config() {
        Registry registry = new Registry(
            Map.of("loan", new Account("loan", "AUD", BalanceSource.STATEMENT, 7),
                "offset", new Account("offset", "AUD", BalanceSource.STATEMENT, 7)),
            Map.of("ron", new User("ron", "Ron", true, "weekly")));
        TransferRules rules = new TransferRules(4, 0, 0, 30, 0.5, Map.of());
        return new DeriveConfig(registry,
            RuleSet.compile("t.yaml", new RuleSet.File(List.of("FOO"), List.of())),
            rules, Profiles.empty(), "sha256:cfg");
    }

    private static Fact fact(long n, String id, String account, long amount, long balance) {
        return new Fact(n, id, account, LocalDate.of(2026, 9, 1), amount, balance, "row " + id, null, 0,
            Observation.POSTED, "test", Provenance.BANK, null, "test/1", ASOF);
    }

    /** The drawdown on the loan, and the rollover on the offset that closed it. */
    private static List<Fact> facts() {
        return List.of(
            fact(1, "loan-open", "loan", -100000, -160000),
            fact(2, "rollover", "offset", -160000, 0));
    }

    private static Decision.CloseAccount close(String account, long closing, List<String> settles) {
        return new Decision.CloseAccount(3, account, CLOSED, closing, settles, "closed", Actor.USER, "ron",
            ASOF);
    }

    @Test
    void aClosureLandsOnTheDeclaredBalanceAndPairsItsSettlingLeg() {
        Derivation d = Derive.derive(facts(), List.of(close("loan", 0, List.of("rollover"))), config(), ASOF);

        ClosedAccount c = d.closure("loan").orElseThrow();
        assertEquals(0, c.closingBalance());
        assertEquals(160000, c.adjustment(), "0 − (−160,000)");

        // The settling leg pairs directly, the clearing shape: one real leg + an account side.
        assertEquals(1, d.transfers().size());
        assertEquals("loan", d.transfers().getFirst().clearingAccount());
        assertEquals("rollover", d.transfers().getFirst().fromLeg());
        assertEquals("loan", d.transfers().getFirst().toLeg());
        assertEquals(Confidence.EXACT, d.transfers().getFirst().confidence());

        CurrentFact leg = d.current("rollover").orElseThrow();
        assertEquals(LegState.MATCHED, leg.leg());

        // The closure is a derived row, never a fact: a reserved id, the declared closing as balance.
        ClearingLeg derived = d.clearingLegs().stream()
            .filter(l -> l.accountRef().equals("loan")).findFirst().orElseThrow();
        assertEquals("cls|loan|rollover", derived.legId());
        assertEquals(160000, derived.amount());
        assertEquals(0, derived.balance());
        assertTrue(d.current("cls|loan|rollover").isEmpty(), "a derived leg is never a current fact");
    }

    @Test
    void theChainStillReconcilesToTheDeclaredClosing() {
        List<Fact> facts = facts();
        Reconciliation.AccountResult r = Reconciliation.reconcile(facts, List.of(), java.util.Set.of(),
            Map.of(), Map.of("loan", new Reconciliation.Closure(CLOSED, 160000, 0))).get("loan");
        assertEquals(Reconciliation.Status.CLOSED, r.status());
        assertEquals(0, r.closing());
        assertTrue(r.balances());
    }

    @Test
    void aClosureWithNoSettlingLegStillAdjustsAndIsReported() {
        Derivation d = Derive.derive(facts(), List.of(close("loan", 0, List.of())), config(), ASOF);
        assertEquals(0, d.closure("loan").orElseThrow().closingBalance());
        assertTrue(d.transfers().isEmpty(), "nothing was named to pair");
        ClearingLeg derived = d.clearingLegs().stream()
            .filter(l -> l.accountRef().equals("loan")).findFirst().orElseThrow();
        assertEquals("cls|loan|" + CLOSED, derived.legId());
        assertEquals(null, derived.transferId(), "unpaired: the row is HELD, not matched");
    }

    @Test
    void aMismatchedSettlementIsIneffectiveAndClosesNothing() {
        Derivation d = Derive.derive(facts(), List.of(close("loan", 0, List.of("loan-open"))), config(), ASOF);
        assertTrue(d.closure("loan").isEmpty());
        assertTrue(d.ineffective().stream()
            .map(IneffectiveDecision::action).anyMatch("CLOSE_ACCOUNT"::equals));
    }

    @Test
    void aDateBeforeTheLastFactIsIneffective() {
        Decision.CloseAccount early = new Decision.CloseAccount(3, "loan", LocalDate.of(2026, 8, 1), 0, List.of(),
            null, Actor.USER, "ron", ASOF);
        Derivation d = Derive.derive(facts(), List.of(early), config(), ASOF);
        assertTrue(d.closure("loan").isEmpty());
        assertTrue(d.ineffective().stream()
            .map(IneffectiveDecision::action).anyMatch("CLOSE_ACCOUNT"::equals));
    }

    @Test
    void revokingTheClosureRestoresTheStatedBalance() {
        Derivation d = Derive.derive(facts(),
            List.of(close("loan", 0, List.of("rollover")),
                new Decision.Revoke(4, 3, "reopened", Actor.USER, "ron", ASOF)),
            config(), ASOF);
        assertTrue(d.closure("loan").isEmpty());
        assertTrue(d.transfers().isEmpty());
        Reconciliation.AccountResult r = Reconciliation.reconcile(facts(), List.of(), java.util.Set.of())
            .get("loan");
        assertEquals(-160000, r.closing());
    }

    @Test
    void theEgressAnchorReportsTheClosure() {
        Derivation d = Derive.derive(facts(), List.of(close("loan", 0, List.of("rollover"))), config(), ASOF);
        Opening.PerAccount p = Opening.of(d.current().stream().map(CurrentFact::fact).toList(),
            config().registry(), d.transfers(), d.closures()).stream()
            .filter(x -> x.accountRef().equals("loan")).findFirst().orElseThrow();
        assertEquals(0, p.latestBalance());
        assertEquals(CLOSED, p.latestAt());
        assertFalse(p.clearing());
    }
}
