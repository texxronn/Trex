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
import trex.v2.core.derive.CurrentFact;
import trex.v2.core.derive.Derivation;
import trex.v2.core.derive.Derive;
import trex.v2.core.derive.LegState;
import trex.v2.core.derive.ReviewItem;
import trex.v2.core.derive.Role;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Account profiles classify a row as a non-posting (V2-PROPOSAL.md §6.9): the facts are untouched,
 * but the row never pairs, never becomes a unit, and can never serve as another leg's contra.
 */
class ProfileRoleTest {

    private static final Instant ASOF = Instant.parse("2026-10-01T00:00:00Z");

    private static DeriveConfig config(Profiles profiles) {
        Registry registry = new Registry(
            Map.of("ing-variable-rate", new Account("ing-variable-rate", "AUD", BalanceSource.STATEMENT, 7),
                "ing-savings", new Account("ing-savings", "AUD", BalanceSource.STATEMENT, 7)),
            Map.of("ron", new User("ron", "Ron", true, "weekly")));
        return new DeriveConfig(registry,
            RuleSet.compile("t.yaml", new RuleSet.File(List.of("FEES"), List.of())),
            TransferRules.defaults(List.of("Internal Transfer")), profiles, "sha256:cfg");
    }

    private static Fact fact(long n, String id, String account, LocalDate date, long amount, String raw) {
        return new Fact(n, id, account, date, amount, 0, raw, null, 0,
            Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", ASOF);
    }

    @Test
    void aProfileRuleMakesTheRowANoopNotAPosting() {
        Profiles profiles = new Profiles(List.of(new Profiles.Rule("ing-variable-rate",
            Pattern.compile("Orange Advantage annual fee", Pattern.CASE_INSENSITIVE), "reference")));
        Derivation d = Derive.derive(List.of(
            fact(1, "fee", "ing-variable-rate", LocalDate.of(2023, 2, 9), -29900,
                "Orange Advantage annual fee - Receipt No 900068"),
            fact(2, "repay", "ing-variable-rate", LocalDate.of(2023, 2, 22), 38696,
                "Repayment - Direct Credit - Receipt No 1")),
            List.of(), config(profiles), ASOF);

        assertEquals(Role.NOOP, d.current("fee").orElseThrow().role());
        assertEquals(Role.TRANSACTION, d.current("repay").orElseThrow().role());

        // The noop row is not a unit; the transaction still is.
        assertTrue(d.units().stream().noneMatch(u -> u.unitId().equals("fee")));
        assertTrue(d.units().stream().anyMatch(u -> u.unitId().equals("repay")));
    }

    @Test
    void aNoopRowNeverJoinsTheTransferPool() {
        Profiles profiles = new Profiles(List.of(new Profiles.Rule("ing-variable-rate",
            Pattern.compile("fee", Pattern.CASE_INSENSITIVE), "reference")));
        Derivation d = Derive.derive(List.of(
            fact(1, "a", "ing-variable-rate", LocalDate.of(2023, 2, 9), -29900, "Fee line"),
            fact(2, "b", "ing-savings", LocalDate.of(2023, 2, 9), 29900, "Internal Transfer - Receipt 1")),
            List.of(), config(profiles), ASOF);

        CurrentFact noop = d.current("a").orElseThrow();
        assertEquals(Role.NOOP, noop.role());
        assertTrue(d.transfers().isEmpty());
        // b waits for a contra that can never be the noop row; only b is held.
        assertEquals(LegState.HELD, d.current("b").orElseThrow().leg());
        assertTrue(d.review().stream()
            .filter(r -> r.kind().equals(ReviewItem.UNMATCHED_LEG))
            .allMatch(r -> r.subject().equals("b")));
    }

    @Test
    void anUnlistedAccountKeepsItsRole() {
        Profiles profiles = new Profiles(List.of(new Profiles.Rule("ing-variable-rate",
            Pattern.compile("fee", Pattern.CASE_INSENSITIVE), "reference")));
        Derivation d = Derive.derive(List.of(
            fact(1, "x", "ing-savings", LocalDate.of(2023, 2, 9), -29900, "Fee line")),
            List.of(), config(profiles), ASOF);
        assertEquals(Role.TRANSACTION, d.current("x").orElseThrow().role());
    }
}
