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
import trex.v2.core.derive.TransferRow;

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
            TransferRules.defaults(List.of("Internal Transfer", "Transfer")),
            trex.v2.core.config.Profiles.empty(), "sha256:cfg1");
    }

    private static Fact fact(long n, String id, String account, LocalDate date, long amount,
                             String raw, String receipt, int occ) {
        return new Fact(n, id, account, date, amount, 0, raw, receipt, occ,
            Observation.POSTED, "test", Provenance.BANK, null, "test/1", Instant.parse("2026-09-30T00:00:00Z"));
    }

    private static Decision.Pin pin(long n, String category, String... ids) {
        return new Decision.Pin(n, List.of(ids), category, null, Actor.USER, "ron", ASOF);
    }

    private static Decision.Note note(long n, String id, String text) {
        return new Decision.Note(n, id, text, Actor.USER, "ron", ASOF);
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
    void notesAccumulateRevokeAndResolveThroughSupersession() {
        List<Fact> facts = List.of(fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", null, 0));

        // Two notes on one row accumulate as a thread, oldest first (a note is not a classification).
        Derivation thread = Derive.derive(facts, List.of(note(2, "a", "first"), note(3, "a", "second")),
            config(), ASOF);
        assertEquals(List.of("first", "second"), thread.notes().stream().map(n -> n.text()).toList());
        assertEquals(List.of("a", "a"), thread.notes().stream().map(n -> n.externalId()).toList());

        // REVOKE removes exactly one.
        Derivation revoked = Derive.derive(facts,
            List.of(note(2, "a", "first"), note(3, "a", "second"),
                new Decision.Revoke(4, 2, null, Actor.USER, "ron", ASOF)),
            config(), ASOF);
        assertEquals(List.of("second"), revoked.notes().stream().map(n -> n.text()).toList());

        // SUPERSEDE carries the note to the current row.
        List<Fact> both = List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", null, 0),
            fact(5, "b", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", null, 1));
        Derivation carried = Derive.derive(both,
            List.of(note(2, "a", "carried"),
                new Decision.Supersede(3, "a", "b", "reparse", Actor.SYSTEM, null, ASOF)),
            config(), ASOF);
        assertEquals("b", carried.notes().get(0).externalId());
    }

    @Test
    void aNoteOnAnUnknownIdIsIneffective() {
        Derivation d = Derive.derive(
            List.of(fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", null, 0)),
            List.of(note(2, "ghost", "nowhere")), config(), ASOF);
        assertTrue(d.notes().isEmpty());
        assertTrue(d.ineffective().stream().anyMatch(i -> i.action().equals(Action.NOTE.wire())));
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
    void theMatcherNeverComparesTextAcrossAccounts() {
        // §9.9.C.3: the pool pairs on amount, sign, account, currency and date alone. The v1 T2/T3
        // equal-`transferStem` tier is retired; the shape pre-filter is what keeps ordinary rows out
        // of the pool, not a text comparison.
        List<Fact> facts = List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "Transfer to Savings 4321", null, 0),
            fact(2, "b", "ing-orange", LocalDate.of(2026, 9, 1), 1000, "Transfer from Other 9999", null, 0));
        Derivation d = Derive.derive(facts, List.of(), config(), ASOF);
        assertEquals(LegState.MATCHED, d.current("a").orElseThrow().leg());
        assertEquals(1, d.transfers().size());
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
    void aReusedReceiptShapesNothingWithoutAPlausibleCounterpart() {
        // The same receipt recurs across accounts and years; it shapes only with a plausible
        // counterpart (opposite sign, equal amount, same currency, within the window, §9.9.C.2).
        List<Fact> facts = List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2022, 1, 1), -66265, "UBS PAYROLL", "901371", 0),
            fact(2, "b", "ing-orange", LocalDate.of(2024, 11, 30), -872, "COLES 1234", "901371", 0));
        Derivation d = Derive.derive(facts, List.of(), config(), ASOF);
        assertTrue(d.transfers().isEmpty(), "a collision is not a pair");
        assertEquals(LegState.EXTERNAL, d.current("a").orElseThrow().leg());
        assertEquals(LegState.EXTERNAL, d.current("b").orElseThrow().leg());
    }

    @Test
    void twoSameDayCandidatesAreAmbiguousNotPaired() {
        List<Fact> facts = List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "Transfer to Savings 4321", null, 0),
            fact(2, "b", "ing-orange", LocalDate.of(2026, 9, 1), 1000, "Transfer from Savings 4321", null, 0),
            fact(3, "c", "cash-ron", LocalDate.of(2026, 9, 1), 1000, "Transfer from Savings 4321", null, 0));
        Derivation d = Derive.derive(facts, List.of(), config(), ASOF);
        assertTrue(d.transfers().isEmpty(), "a tie is never picked");
        assertEquals(LegState.HELD, d.current("a").orElseThrow().leg());
        assertTrue(d.review().stream()
            .anyMatch(r -> r.kind().equals(trex.v2.core.derive.ReviewItem.AMBIGUOUS_TRANSFER)),
            d.review().toString());
    }

    @Test
    void twoPairsSharingAReceiptKeepDistinctTransfers() {
        // A receipt is not unique across transfers (§8.3: that is why the date is in the natural
        // key). Two decision pairs sharing one must each keep a distinct transfer row — the second
        // id falls back to the leg hash rather than overwriting the first and dropping its money.
        List<Fact> facts = List.of(
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -500, "Transfer to Orange", "REC-9", 0),
            fact(2, "b", "ing-orange", LocalDate.of(2026, 9, 1), 500, "Transfer from Savings", "REC-9", 0),
            fact(3, "c", "ing-savings", LocalDate.of(2026, 9, 2), -700, "Transfer to Orange", "REC-9", 0),
            fact(4, "d", "ing-orange", LocalDate.of(2026, 9, 2), 700, "Transfer from Savings", "REC-9", 0));
        List<Decision> decisions = List.of(
            new Decision.Pair(5, "a", "b", "one", Actor.USER, "ron", ASOF),
            new Decision.Pair(6, "c", "d", "two", Actor.USER, "ron", ASOF));
        Derivation d = Derive.derive(facts, decisions, config(), ASOF);
        assertEquals(2, d.transfers().size(), "the second pair must not overwrite the first");
        assertEquals(2, d.transfers().stream().map(TransferRow::transferId).distinct().count());
        for (String id : List.of("a", "b", "c", "d")) {
            assertEquals(LegState.MATCHED, d.current(id).orElseThrow().leg(), id);
        }
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
        List<Fact> facts = List.of(
            // same stem, same day/amount -> a duplicate of the same merchant reading
            fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", null, 0),
            fact(2, "b", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "Coles 1234", null, 1),
            // different stem, same day/amount, similar tokens -> a different reading (§8.4)
            fact(3, "c", "ing-savings", LocalDate.of(2026, 9, 2), -1000, "COLES 1234", null, 0),
            fact(4, "d", "ing-savings", LocalDate.of(2026, 9, 2), -1000, "COLES EXPRESS 1234", null, 0));
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
        assertEquals("3\u00d7 COFFEE CART, SYDNEY on 2026-09-01", dups.getFirst().detail());

        // review_item keys on (subject, kind); the derivation must never emit a duplicate key,
        // or the index's insert fails with a primary-key violation.
        long distinct = d.review().stream().map(r -> r.kind() + "|" + r.subject()).distinct().count();
        assertEquals(d.review().size(), distinct, "duplicate review keys: " + d.review());
    }

    @Test
    void aBrokenChainOpensABalanceBreakNamedByAccount() {
        Derivation d = Derive.derive(List.of(
            balanceFact(1, "a", -100, 900, "COLES 1234"),
            balanceFact(2, "b", 500, 2000, "SOMETHING ELSE")), List.of(), config(), ASOF);

        List<trex.v2.core.derive.ReviewItem> breaks = d.review().stream()
            .filter(r -> r.kind().equals(trex.v2.core.derive.ReviewItem.BALANCE_BREAK)).toList();
        assertEquals(1, breaks.size(), d.review().toString());
        assertEquals("ing-savings", breaks.getFirst().subject());
    }

    @Test
    void aBalanceBreakIsDismissedByAccountAndReturnsOnANewerFact() {
        List<Fact> facts = new ArrayList<>(List.of(
            balanceFact(1, "a", -100, 900, "COLES 1234"),
            balanceFact(2, "b", 500, 2000, "SOMETHING ELSE")));
        Decision.Dismiss dismiss = new Decision.Dismiss(3, trex.v2.core.derive.ReviewItem.BALANCE_BREAK,
            List.of("ing-savings"), "known bank quirk", Actor.USER, "ron", ASOF);

        Derivation silenced = Derive.derive(facts, List.of(dismiss), config(), ASOF);
        assertTrue(silenced.review().stream()
            .noneMatch(r -> r.kind().equals(trex.v2.core.derive.ReviewItem.BALANCE_BREAK)),
            silenced.review().toString());

        // A newer fact on the account ages past the DISMISS, so the item returns.
        facts.add(balanceFact(4, "c", -200, 1800, "LATER SPEND"));
        Derivation back = Derive.derive(facts, List.of(dismiss), config(), ASOF);
        assertTrue(back.review().stream()
            .anyMatch(r -> r.kind().equals(trex.v2.core.derive.ReviewItem.BALANCE_BREAK)),
            back.review().toString());
    }

    @Test
    void aBalanceBreakDismissNamingAnUnknownAccountIsIneffective() {
        Derivation d = Derive.derive(List.of(
            balanceFact(1, "a", -100, 900, "COLES 1234"),
            balanceFact(2, "b", 500, 2000, "SOMETHING ELSE")),
            List.of(new Decision.Dismiss(3, trex.v2.core.derive.ReviewItem.BALANCE_BREAK,
                List.of("cash-nobody"), "typo", Actor.USER, "ron", ASOF)), config(), ASOF);
        assertTrue(d.ineffective().stream().anyMatch(i -> i.action().equals(Action.DISMISS.wire())),
            d.ineffective().toString());
    }

    private static Fact balanceFact(long n, String id, long amount, long balance, String raw) {
        return new Fact(n, id, "ing-savings", LocalDate.of(2026, 9, (int) n), amount, balance, raw, null, 0,
            Observation.POSTED, "test", Provenance.BANK, null, "test/1", Instant.parse("2026-09-30T00:00:00Z"));
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
