package trex.v2.core;

import org.junit.jupiter.api.Test;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.TransferRules;
import trex.v2.core.config.User;
import trex.v2.core.derive.CategoryOrigin;
import trex.v2.core.derive.Confidence;
import trex.v2.core.derive.CurrentFact;
import trex.v2.core.derive.Derivation;
import trex.v2.core.derive.Derive;
import trex.v2.core.derive.LegState;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The P0 core invariants (V2-PROPOSAL.md §15.1–15.4): derive is pure, derive∘derive = derive,
 * decisions win and ids are chain-resolved.
 */
class DeriveTest {

    private static final Instant ASOF = Instant.parse("2026-10-01T00:00:00Z");

    // ---- fixtures ---------------------------------------------------------------------------

    private static Registry registry() {
        List<Account> accounts = List.of(
            new Account("ing-savings", "AUD", BalanceSource.STATEMENT, 7),
            new Account("ing-orange", "AUD", BalanceSource.STATEMENT, 7),
            new Account("cash-ron", "AUD", BalanceSource.DECLARED, 7));
        List<User> users = List.of(
            new User("ron", "Ron", true, "weekly"),
            new User("mel", "Mel", true, "weekly"));
        return new Registry(
            accounts.stream().collect(java.util.stream.Collectors.toMap(Account::ref, a -> a)),
            users.stream().collect(java.util.stream.Collectors.toMap(User::id, u -> u)));
    }

    private static RuleSet rules() {
        return RuleSet.compile("test-categories.yaml", new RuleSet.File(
            List.of("GROCERIES", "SALARY", "TAXES", "FOO"),
            List.of(
                new RuleSet.RuleEntry("GROCERIES", "food", new RuleSet.WhenEntry(null, null, null, null,
                    "COLES|WOOLWORTHS|Market", "raw", null, null, null, null)),
                new RuleSet.RuleEntry("SALARY", "pay", new RuleSet.WhenEntry(null, null, null, null,
                    "salary", "raw", "in", null, null, null)))));
    }

    private static DeriveConfig config() {
        return new DeriveConfig(registry(), rules(),
            TransferRules.defaults(List.of("Internal Transfer", "Transfer")), "sha256:cfg1");
    }

    private static Fact fact(long n, String id, String account, LocalDate date, long amount,
                             String raw, String receipt, int occ) {
        return new Fact(n, id, account, date, amount, 0, raw, receipt, occ,
            Observation.POSTED, "test", Provenance.BANK, null, "test/1", Instant.parse("2026-09-30T00:00:00Z"));
    }

    private static Decision.Pin pin(long n, String category, String... ids) {
        return new Decision.Pin(n, List.of(ids), category, null, Actor.USER, "ron", ASOF);
    }

    // ---- tests ------------------------------------------------------------------------------

    @Test
    void deriveIsPure() {
        List<Fact> facts = List.of(fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", null, 0));
        Derivation first = Derive.derive(facts, List.of(), config(), ASOF);
        Derivation second = Derive.derive(facts, List.of(), config(), ASOF);
        assertEquals(first, second);
    }

    @Test
    void deriveComposeDeriveIsDerive() {
        List<Fact> facts = List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", null, 0),
            fact(2, "b", "ing-orange", LocalDate.of(2026, 9, 1), 1000, "Transfer to Savings", null, 0));
        List<Decision> decisions = List.of(pin(3, "FOO", "a"));
        Derivation once = Derive.derive(facts, decisions, config(), ASOF);
        Derivation twice = Derive.derive(facts, decisions, config(), ASOF);
        assertEquals(once.currentById(), twice.currentById());
        assertEquals(once.categories(), twice.categories());
    }

    @Test
    void ruleAssignsCategory() {
        Derivation d = Derive.derive(List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", null, 0)),
            List.of(), config(), ASOF);
        CurrentFact c = d.current("a").orElseThrow();
        assertEquals("GROCERIES", c.category());
        assertEquals(CategoryOrigin.RULE, c.categoryOrigin());
    }

    @Test
    void decisionsWinOverRules() {
        List<Fact> facts = List.of(fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", null, 0));
        Derivation pinned = Derive.derive(facts, List.of(pin(2, "FOO", "a")), config(), ASOF);
        assertEquals("FOO", pinned.current("a").orElseThrow().category());
        assertEquals(CategoryOrigin.PIN, pinned.current("a").orElseThrow().categoryOrigin());

        Derivation unpinned = Derive.derive(facts,
            List.of(pin(2, "FOO", "a"), new Decision.Unpin(3, List.of("a"), null, Actor.USER, "ron", ASOF)),
            config(), ASOF);
        assertEquals("GROCERIES", unpinned.current("a").orElseThrow().category());
    }

    @Test
    void pairDecisionMatchesLegsAndUnpairReleasesThem() {
        List<Fact> facts = List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "Transfer to Savings 4321", null, 0),
            fact(2, "b", "ing-orange", LocalDate.of(2026, 9, 1), 1000, "Transfer from Savings 4321", null, 0));
        Decision pair = new Decision.Pair(3, "a", "b", "internal", Actor.USER, "ron", ASOF);
        Derivation paired = Derive.derive(facts, List.of(pair), config(), ASOF);
        assertEquals(LegState.MATCHED, paired.current("a").orElseThrow().leg());
        assertEquals(1, paired.transfers().size());
        assertEquals(Confidence.MANUAL, paired.transfers().getFirst().confidence());
        assertEquals("TRANSFER", paired.current("a").orElseThrow().category());

        Derivation unpaired = Derive.derive(facts,
            List.of(pair, new Decision.Unpair(4, "a", "b", null, Actor.USER, "ron", ASOF)),
            config(), ASOF);
        assertEquals(LegState.EXTERNAL, unpaired.current("a").orElseThrow().leg());
        assertTrue(unpaired.transfers().isEmpty());
    }

    @Test
    void theMatcherRequiresEqualTransferStemUnlikeV1() {
        // v1's Matcher paired same-day, equal-magnitude, opposite-sign shaped legs on amount and date
        // alone. v2 (§9.9.C) additionally requires an equal transferStem after removing the transfer
        // vocabulary. A deliberate divergence, pinned so it stays a choice rather than drift.
        List<Fact> facts = List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "Transfer to Savings 4321", null, 0),
            fact(2, "b", "ing-orange", LocalDate.of(2026, 9, 1), 1000, "Transfer from Other 9999", null, 0));
        Derivation d = Derive.derive(facts, List.of(), config(), ASOF);
        assertTrue(d.transfers().isEmpty(), "different merchant stems do not auto-pair in v2");
        assertEquals(LegState.HELD, d.current("a").orElseThrow().leg());
        assertEquals(LegState.HELD, d.current("b").orElseThrow().leg());
    }

    @Test
    void automaticMatcherPairsSameDayOppositeAmounts() {
        List<Fact> facts = List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "Transfer to Savings 4321", null, 0),
            fact(2, "b", "ing-orange", LocalDate.of(2026, 9, 1), 1000, "Transfer from Savings 4321", null, 0));
        Derivation d = Derive.derive(facts, List.of(), config(), ASOF);
        assertEquals(LegState.MATCHED, d.current("a").orElseThrow().leg());
        assertEquals(1, d.transfers().size());
        assertEquals(Confidence.HIGH, d.transfers().getFirst().confidence());
    }

    @Test
    void unpairIsNotRematched() {
        List<Fact> facts = List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "Transfer to Savings 4321", null, 0),
            fact(2, "b", "ing-orange", LocalDate.of(2026, 9, 1), 1000, "Transfer from Savings 4321", null, 0));
        Derivation d = Derive.derive(facts,
            List.of(new Decision.Unpair(3, "a", "b", "not a transfer", Actor.USER, "ron", ASOF)),
            config(), ASOF);
        assertTrue(d.transfers().isEmpty());
        assertEquals(LegState.EXTERNAL, d.current("a").orElseThrow().leg());
    }

    @Test
    void retireRemovesFromCurrentAndRevokeRestores() {
        List<Fact> facts = List.of(fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", null, 0));
        Decision retire = new Decision.Retire(2, "a", "double up", Actor.SYSTEM, null, ASOF);
        Derivation retired = Derive.derive(facts, List.of(retire), config(), ASOF);
        assertTrue(retired.current("a").isEmpty());

        Derivation restored = Derive.derive(facts,
            List.of(retire, new Decision.Revoke(3, 2, "it was real", Actor.USER, "ron", ASOF)),
            config(), ASOF);
        assertTrue(restored.current("a").isPresent());
    }

    @Test
    void supersessionResolvesDecisionsThroughTheChain() {
        List<Fact> facts = List.of(
            fact(1, "old", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", null, 0),
            fact(2, "new", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234 SYDNEY", null, 0));
        Decision supersede = new Decision.Supersede(3, "old", "new", "parser fix", Actor.SYSTEM, null, ASOF);
        Decision pinOld = pin(4, "FOO", "old");
        Derivation d = Derive.derive(facts, List.of(supersede, pinOld), config(), ASOF);
        assertTrue(d.current("old").isEmpty());
        assertEquals("FOO", d.current("new").orElseThrow().category(),
            "a pin naming a superseded id must resolve through the chain");
        assertEquals("new", d.chainResolved().get("old"));
    }

    @Test
    void revokeOfRevokeRestores() {
        List<Fact> facts = List.of(fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", null, 0));
        Decision pin = pin(2, "FOO", "a");
        Decision revoke = new Decision.Revoke(3, 2, null, Actor.USER, "ron", ASOF);
        Decision revokeRevoke = new Decision.Revoke(4, 3, null, Actor.USER, "mel", ASOF);
        Derivation d = Derive.derive(facts, List.of(pin, revoke, revokeRevoke), config(), ASOF);
        assertEquals("FOO", d.current("a").orElseThrow().category());
        assertEquals(CategoryOrigin.PIN, d.current("a").orElseThrow().categoryOrigin());
    }

    @Test
    void noCategoryOnTheFact() {
        // The model itself: a Fact has no category, state or flags field. This is a compile-time
        // statement expressed as documentation; the derivation is the only place an answer exists.
        Fact f = fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", null, 0);
        assertNotNull(f.rawDescription());
        assertFalse(f.toString().contains("category"));
    }

    @Test
    void pendingFactsAreNeverCurrent() {
        Fact pending = new Fact(1, "p", "ing-savings", LocalDate.of(2026, 9, 1), -4995, 0,
            "AUTHORISATION ONLY BP", null, 0, Observation.PENDING, "test", Provenance.BANK, null, "test/1",
            Instant.parse("2026-09-30T00:00:00Z"));
        Derivation d = Derive.derive(List.of(pending), List.of(), config(), ASOF);
        assertTrue(d.current().isEmpty());
    }

    @Test
    void ineffectiveDecisionIsSurfaced() {
        List<Fact> facts = List.of(fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", null, 0));
        Decision badPin = pin(2, "FOO", "ghost");
        Derivation d = Derive.derive(facts, List.of(badPin), config(), ASOF);
        assertFalse(d.ineffective().isEmpty());
        assertEquals("2", d.review().stream()
            .filter(r -> r.kind().equals(trex.v2.core.derive.ReviewItem.INEFFECTIVE_DECISION))
            .findFirst().orElseThrow().subject());
    }

    @Test
    void restatementAndDuplicateAreDerived() {
        List<Fact> facts = new ArrayList<>();
        facts.add(fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", null, 0));
        facts.add(fact(2, "b", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "Coles 1234", null, 1));
        Derivation d = Derive.derive(facts, List.of(), config(), ASOF);
        Map<String, Long> byKind = d.review().stream()
            .collect(java.util.stream.Collectors.groupingBy(r -> r.kind(), java.util.stream.Collectors.counting()));
        assertEquals(1L, byKind.getOrDefault(trex.v2.core.derive.ReviewItem.POTENTIAL_DUP, 0L));
        assertEquals(1L, byKind.getOrDefault(trex.v2.core.derive.ReviewItem.RESTATEMENT, 0L));
    }

    @Test
    void identicalRowsOnADayAreOneDuplicateClusterWithUniqueKeys() {
        List<Fact> facts = List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -450, "COFFEE CART, SYDNEY", null, 0),
            fact(2, "b", "ing-savings", LocalDate.of(2026, 9, 1), -450, "COFFEE CART, SYDNEY", null, 1),
            fact(3, "c", "ing-savings", LocalDate.of(2026, 9, 1), -450, "COFFEE CART, SYDNEY", null, 2));
        Derivation d = Derive.derive(facts, List.of(), config(), ASOF);

        List<trex.v2.core.derive.ReviewItem> dups = d.review().stream()
            .filter(r -> r.kind().equals(trex.v2.core.derive.ReviewItem.POTENTIAL_DUP)).toList();
        assertEquals(1, dups.size(), "one cluster, not one item per pair: " + d.review());
        assertEquals("a", dups.getFirst().subject());
        assertEquals("a,b,c", dups.getFirst().detail());

        // review_item keys on (subject, kind); the derivation must never emit a duplicate key,
        // or the index's insert fails with a primary-key violation.
        long distinct = d.review().stream().map(r -> r.kind() + "|" + r.subject()).distinct().count();
        assertEquals(d.review().size(), distinct, "duplicate review keys: " + d.review());
    }

    @Test
    void aDismissNamingAnUnknownIdIsSurfacedAsIneffective() {
        List<Fact> facts = List.of(fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", null, 0));
        Decision.Dismiss typo = new Decision.Dismiss(2, trex.v2.core.derive.ReviewItem.POTENTIAL_DUP,
            List.of("ghost"), "typo", Actor.USER, "ron", ASOF);
        Derivation d = Derive.derive(facts, List.of(typo), config(), ASOF);
        assertTrue(d.review().stream().anyMatch(r ->
            r.kind().equals(trex.v2.core.derive.ReviewItem.INEFFECTIVE_DECISION) && r.subject().equals("2")),
            d.review().toString());
        assertTrue(d.ineffective().stream().anyMatch(x -> x.decisionN() == 2L));
    }
}
