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
import trex.v2.core.derive.Derivation;
import trex.v2.core.derive.Derive;
import trex.v2.core.derive.LegState;
import trex.v2.core.derive.ReviewItem;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §9.9.C.4: rails tag every shaped and rail-only leg; a pair records the payer's method. */
class RailTest {

    private static final Instant ASOF = Instant.parse("2026-10-01T00:00:00Z");

    private static TransferPattern pattern(String match, Rail rail, boolean shape) {
        return new TransferPattern(match,
            Pattern.compile(match, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), rail, shape);
    }

    private static DeriveConfig config() {
        Registry registry = new Registry(
            Map.of("ing-orange", new Account("ing-orange", "AUD", BalanceSource.STATEMENT, 7),
                "cba-smartaccess", new Account("cba-smartaccess", "AUD", BalanceSource.STATEMENT, 7)),
            Map.of("ron", new User("ron", "Ron", true, "weekly")));
        TransferRules rules = new TransferRules(4, 0, 0, 30, 0.5, Map.of(
            "ing-orange", List.of(pattern("^Rohan Machado - Osko Payment to", Rail.OSKO, true)),
            "cba-smartaccess", List.of(pattern("Fast Transfer", Rail.BANK_TRANSFER, true)),
            "default", List.of(pattern("Osko Payment", Rail.OSKO, false))));
        return new DeriveConfig(registry,
            RuleSet.compile("t.yaml", new RuleSet.File(List.of("FOO"), List.of())),
            rules, Profiles.empty(), "sha256:cfg");
    }

    private static Fact fact(long n, String id, String account, LocalDate date, long amount, String raw) {
        return new Fact(n, id, account, date, amount, 0, raw, null, 0,
            Observation.POSTED, "test", Provenance.BANK, null, "test/1", ASOF);
    }

    @Test
    void aRailOnlyPatternTagsWithoutEnteringThePool() {
        Derivation d = Derive.derive(List.of(
            fact(1, "a", "ing-orange", LocalDate.of(2026, 9, 1), -1000,
                "Osko Payment to GLEN MACHADO +61-412658030")), List.of(), config(), ASOF);
        assertEquals(Rail.OSKO, d.current("a").orElseThrow().rail());
        assertEquals(LegState.EXTERNAL, d.current("a").orElseThrow().leg());
        assertTrue(d.review().stream().noneMatch(r -> r.kind().equals(ReviewItem.UNMATCHED_LEG)));
    }

    @Test
    void aMatchedPairRecordsThePayersRail() {
        Derivation d = Derive.derive(List.of(
            fact(1, "a", "ing-orange", LocalDate.of(2026, 9, 1), -1000, "Rohan Machado - Osko Payment to 4321"),
            fact(2, "b", "cba-smartaccess", LocalDate.of(2026, 9, 1), 1000, "Fast Transfer from 1234")),
            List.of(), config(), ASOF);
        assertEquals(1, d.transfers().size());
        assertEquals(Rail.OSKO, d.transfers().getFirst().method());
        assertEquals(Confidence.HIGH, d.transfers().getFirst().confidence());
        assertEquals(Rail.OSKO, d.current("a").orElseThrow().rail());
        assertEquals(Rail.BANK_TRANSFER, d.current("b").orElseThrow().rail());
    }

    @Test
    void theRailDirectionIsTheSign() {
        assertEquals("OUT", Rail.direction(-500));
        assertEquals("IN", Rail.direction(500));
    }
}
