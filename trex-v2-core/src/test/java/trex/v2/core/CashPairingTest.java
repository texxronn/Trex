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
import trex.v2.core.derive.ClearingLeg;
import trex.v2.core.derive.Derivation;
import trex.v2.core.derive.Derive;
import trex.v2.core.derive.LegState;
import trex.v2.core.derive.Reconciliation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cash wallet as a pairing target (V2-CASH-PLAN.md §4.1, D-1/D-2): a {@code clearing:} pattern or
 * an {@code ATTACH_ACCOUNT} may name a {@code declared} account, and a declared account's
 * reconciliation counts its synthetic wallet legs as movements between counts (SPEC §6.3, §6.7).
 */
class CashPairingTest {

    private static final Instant ASOF = Instant.parse("2026-10-01T00:00:00Z");

    private static DeriveConfig config() {
        Registry registry = new Registry(
            Map.of("ing-savings", new Account("ing-savings", "AUD", BalanceSource.STATEMENT, 7),
                "cash-ron", new Account("cash-ron", "AUD", BalanceSource.DECLARED, 7)),
            Map.of("ron", new User("ron", "Ron", true, "weekly")));
        TransferRules rules = new TransferRules(4, 0, 0, 30, 0.5, Map.of(
            "ing-savings", List.of(
                new TransferPattern("ATM",
                    Pattern.compile("ATM", Pattern.CASE_INSENSITIVE), Rail.BANK_TRANSFER, true, "cash-ron"),
                new TransferPattern("Internal Transfer",
                    Pattern.compile("Internal Transfer", Pattern.CASE_INSENSITIVE), Rail.BANK_TRANSFER, true))));
        return new DeriveConfig(registry,
            RuleSet.compile("t.yaml", new RuleSet.File(List.of("FOO"), List.of())),
            rules, Profiles.empty(), "sha256:cfg");
    }

    private static Fact fact(long n, String id, String account, LocalDate date, long amount,
                             long balance, String raw) {
        return new Fact(n, id, account, date, amount, balance, raw, null, 0,
            Observation.POSTED, "test", Provenance.BANK, null, "test/1", ASOF);
    }

    @Test
    void anAtmWithdrawalPairsToTheDeclaredWallet() {
        Derivation d = Derive.derive(List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 15), -10000, 0, "ATM WDL 123")),
            List.of(), config(), ASOF);
        assertEquals(1, d.transfers().size());
        assertEquals("cash-ron", d.transfers().getFirst().clearingAccount());
        assertEquals("a", d.transfers().getFirst().fromLeg());
        assertEquals("cash-ron", d.transfers().getFirst().toLeg());
        assertEquals(LegState.MATCHED, d.current("a").orElseThrow().leg());
        assertEquals(1, d.units().stream().filter(u -> u.unitKind().equals("TRANSFER")).count());
    }

    @Test
    void aCashDepositPairsOutOfTheWallet() {
        Derivation d = Derive.derive(List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 15), 10000, 0, "ATM DEPOSIT 123")),
            List.of(), config(), ASOF);
        assertEquals(1, d.transfers().size());
        assertEquals("cash-ron", d.transfers().getFirst().fromLeg());
        assertEquals("a", d.transfers().getFirst().toLeg());
        assertEquals(LegState.MATCHED, d.current("a").orElseThrow().leg());
    }

    @Test
    void markExternalOnACashDepositKeepsItExternal() {
        List<Fact> facts = List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 15), 10000, 0, "ATM DEPOSIT 123"));
        Derivation d = Derive.derive(facts,
            List.of(new Decision.MarkExternal(2, "a", "a gift", Actor.USER, "ron", ASOF)),
            config(), ASOF);
        assertTrue(d.transfers().isEmpty());
        assertEquals(LegState.EXTERNAL, d.current("a").orElseThrow().leg());
        assertFalse(d.current("a").orElseThrow().matched());
    }

    @Test
    void attachAccountMayTargetTheDeclaredWallet() {
        List<Fact> facts = List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 15), -5000, 0, "Internal Transfer 4321"));
        Derivation attached = Derive.derive(facts,
            List.of(new Decision.AttachAccount(2, List.of("a"), "cash-ron", "cash", Actor.USER, "ron", ASOF)),
            config(), ASOF);
        assertEquals(1, attached.transfers().size());
        assertEquals("cash-ron", attached.transfers().getFirst().clearingAccount());
        assertEquals(LegState.MATCHED, attached.current("a").orElseThrow().leg());

        // A statement target is still refused: the decision is recorded and surfaced, not applied.
        Derivation refused = Derive.derive(facts,
            List.of(new Decision.AttachAccount(2, List.of("a"), "ing-savings", "not pairable",
                Actor.USER, "ron", ASOF)),
            config(), ASOF);
        assertTrue(refused.transfers().isEmpty());
        assertTrue(refused.ineffective().stream()
            .anyMatch(i -> i.action().equals(Action.ATTACH_ACCOUNT.wire())));
    }

    @Test
    void aWalletRowDoesNotPairWithItsOwnWallet() {
        // A `default` pattern applies to every account, the wallet included: an ATM-shaped wallet
        // row must not self-pair. It falls through to the matcher and is never MATCHED.
        DeriveConfig cfg = new DeriveConfig(config().registry(), config().categories(),
            new TransferRules(4, 0, 0, 30, 0.5, Map.of(
                TransferRules.ANY_ACCOUNT, List.of(new TransferPattern("ATM",
                    Pattern.compile("ATM", Pattern.CASE_INSENSITIVE), Rail.BANK_TRANSFER, true, "cash-ron")))),
            Profiles.empty(), "sha256:cfg");
        Derivation d = Derive.derive(List.of(
            fact(1, "c", "cash-ron", LocalDate.of(2026, 9, 15), -10000, 0, "ATM WDL 123")),
            List.of(), cfg, ASOF);
        assertTrue(d.transfers().isEmpty());
        assertFalse(d.current("c").orElseThrow().matched(), "a leg never pairs with its own account");
    }

    @Test
    void theDeclaredGapIncludesTheWalletLegs() {
        List<Fact> facts = List.of(
            fact(1, "att1", "cash-ron", LocalDate.of(2026, 9, 1), 0, 20000, "count"),
            fact(2, "w", "ing-savings", LocalDate.of(2026, 9, 15), -10000, 0, "ATM WDL 123"),
            fact(3, "att2", "cash-ron", LocalDate.of(2026, 9, 30), 0, 30000, "count"));
        Derivation d = Derive.derive(facts, List.of(), config(), ASOF);
        assertEquals("cash-ron", d.transfers().getFirst().clearingAccount());

        // Without the synthetic legs the wallet looks 10000 short of its count.
        Reconciliation.AccountResult withoutLegs = Reconciliation.reconcile(facts, List.of(), Set.of("cash-ron"))
            .get("cash-ron");
        assertEquals(10000, withoutLegs.gap());

        // With them, the movement between the counts is counted: the gap closes.
        Reconciliation.AccountResult withLegs = Reconciliation.reconcile(facts, List.of(), Set.of("cash-ron"),
                Map.of("cash-ron", List.of(new Reconciliation.Movement(LocalDate.of(2026, 9, 15), 2, 10000))))
            .get("cash-ron");
        assertEquals(0, withLegs.gap());

        // The synthetic leg takes its balance from the declared chain, landing on the latest count.
        ClearingLeg leg = d.clearingLegs().stream().filter(l -> l.accountRef().equals("cash-ron"))
            .findFirst().orElseThrow();
        assertEquals(10000, leg.amount());
        assertEquals(30000, leg.balance());

        // A declared gap is never a BALANCE_BREAK.
        assertTrue(d.review().stream().noneMatch(r -> r.kind().equals(
            trex.v2.core.derive.ReviewItem.BALANCE_BREAK) && r.subject().equals("cash-ron")));
    }
}
