package trex.v2.core;

import org.junit.jupiter.api.Test;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.TransferRules;
import trex.v2.core.config.User;
import trex.v2.core.derive.AmountKind;
import trex.v2.core.derive.Cadence;
import trex.v2.core.derive.CategoryOrigin;
import trex.v2.core.derive.Commitment;
import trex.v2.core.derive.CommitmentKind;
import trex.v2.core.derive.CommitmentNote;
import trex.v2.core.derive.CommitmentOccurrence;
import trex.v2.core.derive.CommitmentOrigin;
import trex.v2.core.derive.CommitmentRule;
import trex.v2.core.derive.CommitmentStatus;
import trex.v2.core.derive.Confidence;
import trex.v2.core.derive.CurrentFact;
import trex.v2.core.derive.Derivation;
import trex.v2.core.derive.Derive;
import trex.v2.core.derive.IneffectiveDecision;
import trex.v2.core.derive.LegState;
import trex.v2.core.derive.OccurrenceStatus;
import trex.v2.core.derive.ReviewItem;
import trex.v2.core.derive.TransferRow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void attachAccountPairsALegWithAClearingAccount() {
        java.util.Map<String, Account> accounts = new java.util.LinkedHashMap<>();
        accounts.put("ing-savings", new Account("ing-savings", "AUD", BalanceSource.STATEMENT, 7));
        accounts.put("bw-legacy", new Account("bw-legacy", "AUD", BalanceSource.CLEARING, 7, 0L, null));
        DeriveConfig cfg = new DeriveConfig(new Registry(accounts, registry().users()), rules(),
            TransferRules.defaults(List.of("Internal Transfer", "Transfer")),
            trex.v2.core.config.Profiles.empty(), "sha256:cfg1");
        List<Fact> facts = List.of(fact(1, "a", "ing-savings", LocalDate.of(2026, 8, 1), -1000,
            "Transfer to Card 1234", null, 0));
        Derivation d = Derive.derive(facts,
            List.of(new Decision.AttachAccount(2, List.of("a"), "bw-legacy", "card pre-2024",
                Actor.USER, "ron", ASOF)), cfg, ASOF);
        assertEquals(1, d.transfers().size());
        assertEquals("bw-legacy", d.transfers().getFirst().clearingAccount());
        assertEquals(LegState.MATCHED, d.current("a").orElseThrow().leg());
        assertTrue(d.review().stream().noneMatch(
            r -> r.kind().equals(trex.v2.core.derive.ReviewItem.UNMATCHED_LEG)));
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

    // ---- commitments (Stage 4; V2-COMMITMENTS-PLAN.md §2, §7) --------------------------------

    private static Decision.DeclareCommitment declare(long n, String id, String name, String direction,
                                                      long amount, LocalDate anchor, String... matches) {
        List<Decision.Match> rules = new ArrayList<>();
        for (String match : matches) {
            rules.add(new Decision.Match(match, null));
        }
        return new Decision.DeclareCommitment(n, id, name, direction, Cadence.MONTHLY, AmountKind.FIXED,
            CommitmentKind.BILL, rules, amount, anchor, null, null, Actor.USER, "ron", ASOF);
    }

    private static Commitment commitment(Derivation d, String id) {
        return d.commitments().stream().filter(c -> c.commitmentId().equals(id)).findFirst()
            .orElseThrow(() -> new AssertionError("no commitment " + id + ": " + d.commitments()));
    }

    private static List<CommitmentOccurrence> occurrences(Derivation d, String id) {
        return d.commitmentOccurrences().stream().filter(o -> o.commitmentId().equals(id)).toList();
    }

    private static CommitmentOccurrence at(List<CommitmentOccurrence> occurrences, LocalDate due) {
        return occurrences.stream().filter(o -> o.dueDate().equals(due)).findFirst()
            .orElseThrow(() -> new AssertionError("no occurrence at " + due + ": " + occurrences));
    }

    private static Optional<ReviewItem> item(Derivation d, String kind, String subject) {
        return d.review().stream()
            .filter(r -> r.kind().equals(kind) && r.subject().equals(subject)).findFirst();
    }

    /** Six monthly ACME charges (Jan–Jun 2026) plus a later COLES fact to advance the frontier. */
    private static List<Fact> acmeFacts() {
        List<Fact> facts = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 1, 15);
        for (int i = 0; i < 6; i++) {
            facts.add(fact(i + 1, "acme-" + (i + 1), "ing-savings", date, -10000, "ACME BILL", null, 0));
            date = date.plusMonths(1);
        }
        facts.add(fact(7, "coles", "ing-savings", LocalDate.of(2026, 9, 25), -1234, "COLES 1234", null, 0));
        return facts;
    }

    /** The same fixture with the loan-leg patterns off (false) or on (true) in transfers.yaml. */
    private static DeriveConfig pairingConfig(boolean shaped) {
        TransferRules transfers;
        if (shaped) {
            transfers = new TransferRules(TransferRules.DEFAULT_WINDOW_DAYS, 0, 0,
                TransferRules.DEFAULT_HOLD_WINDOW_DAYS, TransferRules.DEFAULT_RESTATEMENT_OVERLAP,
                Map.of(
                    "ing-orange", List.of(new TransferRules.TransferPattern("HOME LOAN REPAYMENT",
                        java.util.regex.Pattern.compile("HOME LOAN REPAYMENT"), trex.v2.core.Rail.BANK_TRANSFER, true)),
                    "ing-savings", List.of(new TransferRules.TransferPattern("TRANSFER FROM OFFSET",
                        java.util.regex.Pattern.compile("TRANSFER FROM OFFSET"), trex.v2.core.Rail.BANK_TRANSFER, true))));
        } else {
            transfers = TransferRules.defaults(List.of("Internal Transfer", "Transfer"));
        }
        return new DeriveConfig(registry(), rules(), transfers, trex.v2.core.config.Profiles.empty(), "sha256:cfg1");
    }

    @Test
    void aDeclarationMatchesItsFactsAndRaisesTheArrearsAndDormancyItems() {
        List<Fact> facts = acmeFacts();
        Decision declare = declare(10, "acme", "Acme", "out", 10000, LocalDate.of(2026, 1, 15), "ACME");
        Derivation d = Derive.derive(facts, List.of(declare), config(), ASOF);

        Commitment acme = commitment(d, "acme");
        assertEquals(CommitmentOrigin.DECLARED, acme.origin());
        assertEquals(CommitmentStatus.DORMANT, acme.status(), "last paid June, frontier September");
        assertEquals(6, acme.occurrenceCount());
        assertEquals(-60000L, acme.costToDate().longValue());
        assertEquals(-120000L, acme.annualised().longValue());
        assertEquals(3, acme.arrearsCount());
        assertEquals(-30000L, acme.arrearsAmount().longValue());
        assertEquals(List.of(new CommitmentRule("acme", "ACME", null, 10L)), d.commitmentRules());

        List<CommitmentOccurrence> bills = occurrences(d, "acme");
        assertEquals(OccurrenceStatus.OCCURRED, at(bills, LocalDate.of(2026, 6, 15)).status());
        assertEquals(OccurrenceStatus.MISSED, at(bills, LocalDate.of(2026, 7, 15)).status());
        assertEquals(OccurrenceStatus.DUE, at(bills, LocalDate.of(2026, 10, 15)).status());
        assertTrue(bills.stream().allMatch(o -> o.stateHash() != null));

        // The detected candidate is suppressed by rule coverage and never reaches Review.
        assertTrue(d.commitments().stream().noneMatch(c -> c.origin() == CommitmentOrigin.DETECTED));
        assertTrue(d.review().stream().noneMatch(r -> r.kind().equals(ReviewItem.SUSPECTED_RECURRING)));
        assertTrue(item(d, ReviewItem.COMMITMENT_ARREARS, "acme").isPresent(), d.review().toString());
        assertTrue(item(d, ReviewItem.DORMANT_COMMITMENT, "acme").isPresent(), d.review().toString());
        assertTrue(item(d, ReviewItem.COMMITMENT_ARREARS, "acme").orElseThrow().detail().contains("3"));
    }

    @Test
    void reDeclaringReplacesRulesAndHistoryUnderTheSameId() {
        List<Fact> facts = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 1, 15);
        for (int i = 0; i < 4; i++) {
            facts.add(fact(i + 1, "other-" + (i + 1), "ing-savings", date, -20000, "OTHER BILL", null, 0));
            date = date.plusMonths(1);
        }
        facts.add(fact(9, "coles", "ing-savings", LocalDate.of(2026, 9, 25), -1, "COLES 1234", null, 0));

        Derivation d = Derive.derive(facts, List.of(
            declare(10, "plan", "Old plan", "out", 10000, LocalDate.of(2026, 1, 15), "ACME"),
            declare(11, "plan", "New plan", "out", 20000, LocalDate.of(2026, 1, 15), "OTHER")),
            config(), ASOF);

        assertEquals(1, d.commitments().stream().filter(c -> c.commitmentId().equals("plan")).count());
        Commitment plan = commitment(d, "plan");
        assertEquals("New plan", plan.name());
        assertEquals(-20000L, plan.currentAmount().longValue());
        assertEquals(4, plan.occurrenceCount(), "the history is the new declaration's matches");
        assertEquals(List.of(new CommitmentRule("plan", "OTHER", null, 11L)), d.commitmentRules());
        assertTrue(occurrences(d, "plan").stream()
            .filter(o -> o.matchedExternalId() != null)
            .allMatch(o -> o.matchedExternalId().startsWith("other-")));
    }

    @Test
    void aDeclarationWithAnUncompilableRuleIsIneffectiveAndALaterGoodOneTakesOver() {
        List<Fact> facts = acmeFacts();
        Decision.DeclareCommitment bad = new Decision.DeclareCommitment(10, "acme", "Acme", "out",
            Cadence.MONTHLY, AmountKind.FIXED, CommitmentKind.BILL,
            List.of(new Decision.Match("ACME[", null)), 10000L, LocalDate.of(2026, 1, 15),
            null, null, Actor.USER, "ron", ASOF);

        Derivation d = Derive.derive(facts, List.of(bad), config(), ASOF);
        assertTrue(d.commitments().stream().noneMatch(c -> c.commitmentId().equals("acme")),
            d.commitments().toString());
        assertTrue(d.commitmentRules().isEmpty());
        assertTrue(d.ineffective().stream()
            .anyMatch(i -> i.action().equals(Action.DECLARE_COMMITMENT.wire())
                && i.reason().contains("ACME[")), d.ineffective().toString());
        assertTrue(d.review().stream().anyMatch(r -> r.kind().equals(ReviewItem.INEFFECTIVE_DECISION)
            && r.subject().equals("10")), d.review().toString());

        // A later effective good declaration of the same id takes over.
        Derivation fixed = Derive.derive(facts, List.of(bad,
            declare(11, "acme", "Acme fixed", "out", 10000, LocalDate.of(2026, 1, 15), "ACME")),
            config(), ASOF);
        assertEquals("Acme fixed", commitment(fixed, "acme").name());
        assertEquals(List.of(new CommitmentRule("acme", "ACME", null, 11L)), fixed.commitmentRules());

        // An earlier good declaration survives a later bad one: ineffective means no effect.
        Decision.DeclareCommitment badLater = new Decision.DeclareCommitment(13, "acme", "Acme", "out",
            Cadence.MONTHLY, AmountKind.FIXED, CommitmentKind.BILL,
            List.of(new Decision.Match("ACME[", null)), 10000L, LocalDate.of(2026, 1, 15),
            null, null, Actor.USER, "ron", ASOF);
        Derivation kept = Derive.derive(facts, List.of(
            declare(12, "acme", "Acme good", "out", 10000, LocalDate.of(2026, 1, 15), "ACME"), badLater),
            config(), ASOF);
        assertEquals("Acme good", commitment(kept, "acme").name());
        assertEquals(List.of(new CommitmentRule("acme", "ACME", null, 12L)), kept.commitmentRules());
    }

    @Test
    void aRetirementStopsFutureOccurrencesClearsDormancyAndKeepsArrears() {
        List<Fact> facts = acmeFacts();
        Decision declare = declare(10, "acme", "Acme", "out", 10000, LocalDate.of(2026, 1, 15), "ACME");
        Decision retire = new Decision.RetireCommitment(11, "acme", LocalDate.of(2026, 9, 15),
            "cancelled", Actor.USER, "ron", ASOF);

        Derivation ended = Derive.derive(facts, List.of(declare, retire), config(), ASOF);
        Commitment row = commitment(ended, "acme");
        assertEquals(CommitmentStatus.ENDED, row.status());
        assertEquals(LocalDate.of(2026, 9, 15), row.endedAt());
        assertEquals(11L, row.retiredN().longValue());
        assertTrue(occurrences(ended, "acme").stream()
            .noneMatch(o -> o.dueDate().isAfter(LocalDate.of(2026, 9, 15))), "no occurrence after endedAt");
        assertEquals(OccurrenceStatus.MISSED,
            at(occurrences(ended, "acme"), LocalDate.of(2026, 9, 15)).status(),
            "the occurrence on endedAt still counts");
        assertTrue(item(ended, ReviewItem.DORMANT_COMMITMENT, "acme").isEmpty(), "ended commits are never dormant");
        assertTrue(item(ended, ReviewItem.COMMITMENT_ARREARS, "acme").isPresent(), "arrears stay visible");
        assertEquals(3, row.arrearsCount());

        // A declare after a retire revives it; the fields are the new declaration's.
        Derivation revived = Derive.derive(facts, List.of(declare, retire,
            declare(12, "acme", "Acme revived", "out", 10000, LocalDate.of(2026, 1, 15), "ACME")),
            config(), ASOF);
        Commitment again = commitment(revived, "acme");
        assertEquals("Acme revived", again.name());
        assertNull(again.retiredN());
        assertNull(again.endedAt());
        assertEquals(CommitmentStatus.DORMANT, again.status());
        assertTrue(item(revived, ReviewItem.DORMANT_COMMITMENT, "acme").isPresent());

        // REVOKE of the retire restores it too.
        Derivation restored = Derive.derive(facts, List.of(declare, retire,
            new Decision.Revoke(13, 11, "it was still live", Actor.USER, "ron", ASOF)), config(), ASOF);
        assertNull(commitment(restored, "acme").retiredN());

        // A settle on a retired commitment is allowed: a conclusion about the past.
        Derivation settledEnded = Derive.derive(facts, List.of(declare, retire,
            new Decision.SettleOccurrence(14, "acme",
                List.of(LocalDate.of(2026, 7, 15), LocalDate.of(2026, 8, 15), LocalDate.of(2026, 9, 15)),
                "paid in cash", Actor.USER, "ron", ASOF)), config(), ASOF);
        assertEquals(CommitmentStatus.ENDED, commitment(settledEnded, "acme").status());
        assertEquals(0, commitment(settledEnded, "acme").arrearsCount());
        assertTrue(item(settledEnded, ReviewItem.COMMITMENT_ARREARS, "acme").isEmpty());
    }

    @Test
    void ignoreRecurringRemovesTheCandidateAndItStaysGoneUntilRevoked() {
        List<Fact> facts = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 4, 15);
        for (int i = 0; i < 6; i++) {
            facts.add(fact(i + 1, "spotify-" + (i + 1), "ing-orange", date, -1299, "SPOTIFY", null, 0));
            date = date.plusMonths(1);
        }
        Decision ignore = new Decision.IgnoreRecurring(20, "SPOTIFY", "cancelled", Actor.USER, "ron", ASOF);

        Derivation before = Derive.derive(facts, List.of(), config(), ASOF);
        assertTrue(before.commitments().stream().anyMatch(c -> "SPOTIFY".equals(c.candidateKey())));
        assertTrue(item(before, ReviewItem.SUSPECTED_RECURRING, "SPOTIFY").isPresent());

        Derivation ignored = Derive.derive(facts, List.of(ignore), config(), ASOF);
        assertTrue(ignored.commitments().stream().noneMatch(c -> "SPOTIFY".equals(c.candidateKey())));
        assertTrue(item(ignored, ReviewItem.SUSPECTED_RECURRING, "SPOTIFY").isEmpty());

        // A newer fact does not reopen an ignored candidate — that is what REVOKE is for.
        List<Fact> newer = new ArrayList<>(facts);
        newer.add(fact(21, "spotify-7", "ing-orange", LocalDate.of(2026, 9, 20), -1299, "SPOTIFY", null, 0));
        Derivation stillGone = Derive.derive(newer, List.of(ignore), config(), ASOF);
        assertTrue(stillGone.commitments().stream().noneMatch(c -> "SPOTIFY".equals(c.candidateKey())));

        Derivation restored = Derive.derive(newer, List.of(ignore,
            new Decision.Revoke(22, 20, "it is back", Actor.USER, "ron", ASOF)), config(), ASOF);
        assertTrue(restored.commitments().stream().anyMatch(c -> "SPOTIFY".equals(c.candidateKey())),
            restored.commitments().toString());
        assertTrue(item(restored, ReviewItem.SUSPECTED_RECURRING, "SPOTIFY").isPresent());
    }

    @Test
    void aPinPlacesAFactTheRulesMissAndUnpinReleasesIt() {
        List<Fact> facts = List.of(fact(1, "bpay", "ing-savings", LocalDate.of(2026, 6, 20),
            -10000, "BPAY 123456", null, 0));
        Decision declare = declare(10, "bills", "Bills", "out", 10000, LocalDate.of(2026, 3, 15), "ACME");
        Decision pin = new Decision.PinCommitment(20, "bills", List.of("bpay"),
            "the rules miss this", Actor.USER, "ron", ASOF);

        Derivation byRule = Derive.derive(facts, List.of(declare), config(), ASOF);
        assertEquals(OccurrenceStatus.MISSED,
            at(occurrences(byRule, "bills"), LocalDate.of(2026, 3, 15)).status());
        assertNull(at(occurrences(byRule, "bills"), LocalDate.of(2026, 3, 15)).matchedExternalId());

        // A pin allocates like a rule match: the oldest open occurrence takes the fact (§2.9).
        Derivation pinned = Derive.derive(facts, List.of(declare, pin), config(), ASOF);
        CommitmentOccurrence march = at(occurrences(pinned, "bills"), LocalDate.of(2026, 3, 15));
        assertEquals(OccurrenceStatus.OCCURRED, march.status());
        assertEquals("bpay", march.matchedExternalId());
        assertEquals("pin", march.matchedBy());

        Derivation unpinned = Derive.derive(facts, List.of(declare, pin,
            new Decision.UnpinCommitment(21, List.of("bpay"), "back to the rules", Actor.USER, "ron", ASOF)),
            config(), ASOF);
        assertEquals(OccurrenceStatus.MISSED,
            at(occurrences(unpinned, "bills"), LocalDate.of(2026, 3, 15)).status());
        assertTrue(unpinned.ineffective().stream()
            .noneMatch(i -> i.action().equals(Action.UNPIN_COMMITMENT.wire())), "not pinned is harmless");
    }

    @Test
    void unknownOrRetiredCommitmentTargetsAreIneffective() {
        List<Fact> facts = List.of(fact(1, "bpay", "ing-savings", LocalDate.of(2026, 6, 20),
            -10000, "BPAY 123456", null, 0));
        Derivation unknown = Derive.derive(facts, List.of(
            new Decision.RetireCommitment(2, "ghost", LocalDate.of(2026, 6, 1), "gone", Actor.USER, "ron", ASOF),
            new Decision.NoteCommitment(3, "ghost", "hello", Actor.USER, "ron", ASOF),
            new Decision.SettleOccurrence(4, "ghost", List.of(LocalDate.of(2026, 6, 15)), null,
                Actor.USER, "ron", ASOF),
            new Decision.PinCommitment(5, "ghost", List.of("bpay"), null, Actor.USER, "ron", ASOF)),
            config(), ASOF);
        assertEquals(List.of(Action.RETIRE_COMMITMENT.wire(), Action.NOTE_COMMITMENT.wire(),
            Action.SETTLE_OCCURRENCE.wire(), Action.PIN_COMMITMENT.wire()),
            unknown.ineffective().stream().map(IneffectiveDecision::action).toList());
        assertTrue(unknown.commitments().isEmpty());
        assertEquals(4, unknown.review().stream()
            .filter(r -> r.kind().equals(ReviewItem.INEFFECTIVE_DECISION)).count(), unknown.review().toString());

        // A pin naming a retired commitment is ineffective too.
        Derivation retired = Derive.derive(facts, List.of(
            declare(10, "bills", "Bills", "out", 10000, LocalDate.of(2026, 3, 15), "ACME"),
            new Decision.RetireCommitment(11, "bills", LocalDate.of(2026, 6, 30), "done",
                Actor.USER, "ron", ASOF),
            new Decision.PinCommitment(12, "bills", List.of("bpay"), null, Actor.USER, "ron", ASOF)),
            config(), ASOF);
        assertTrue(retired.ineffective().stream()
            .anyMatch(i -> i.action().equals(Action.PIN_COMMITMENT.wire())), retired.ineffective().toString());
        assertEquals(OccurrenceStatus.MISSED,
            at(occurrences(retired, "bills"), LocalDate.of(2026, 6, 15)).status());
    }

    @Test
    void aPinIssuedBeforeARetirementKeepsItsHistoricalPlacement() {
        List<Fact> facts = List.of(fact(1, "bpay", "ing-savings", LocalDate.of(2026, 6, 20),
            -10000, "BPAY 123456", null, 0));
        Derivation d = Derive.derive(facts, List.of(
            declare(10, "bills", "Bills", "out", 10000, LocalDate.of(2026, 3, 15), "ACME"),
            new Decision.PinCommitment(11, "bills", List.of("bpay"), null, Actor.USER, "ron", ASOF),
            new Decision.RetireCommitment(12, "bills", LocalDate.of(2026, 6, 20), "closed",
                Actor.USER, "ron", ASOF)),
            config(), ASOF);
        CommitmentOccurrence march = at(occurrences(d, "bills"), LocalDate.of(2026, 3, 15));
        assertEquals(OccurrenceStatus.OCCURRED, march.status(), "the pin predates the retirement");
        assertEquals("pin", march.matchedBy());
        assertTrue(d.ineffective().stream()
            .noneMatch(i -> i.action().equals(Action.PIN_COMMITMENT.wire())));
        assertEquals(CommitmentStatus.ENDED, commitment(d, "bills").status());
    }

    @Test
    void commitmentNotesAccumulateRevokeAndMayNameARetiredCommitment() {
        Derivation d = Derive.derive(List.of(), List.of(
            declare(10, "acme", "Acme", "out", 10000, LocalDate.of(2026, 1, 15), "ACME"),
            new Decision.NoteCommitment(11, "acme", "first", Actor.USER, "ron", ASOF),
            new Decision.NoteCommitment(12, "acme", "second", Actor.USER, "mel", ASOF),
            new Decision.RetireCommitment(13, "acme", LocalDate.of(2026, 6, 30), "done",
                Actor.USER, "ron", ASOF),
            new Decision.NoteCommitment(14, "acme", "cancelled after price rise", Actor.USER, "ron", ASOF)),
            config(), ASOF);
        assertEquals(List.of("first", "second", "cancelled after price rise"),
            d.commitmentNotes().stream().map(CommitmentNote::text).toList());
        assertEquals(List.of(11L, 12L, 14L),
            d.commitmentNotes().stream().map(CommitmentNote::decisionN).toList());

        Derivation revoked = Derive.derive(List.of(), List.of(
            declare(10, "acme", "Acme", "out", 10000, LocalDate.of(2026, 1, 15), "ACME"),
            new Decision.NoteCommitment(11, "acme", "first", Actor.USER, "ron", ASOF),
            new Decision.NoteCommitment(12, "acme", "second", Actor.USER, "mel", ASOF),
            new Decision.NoteCommitment(14, "acme", "third", Actor.USER, "ron", ASOF),
            new Decision.Revoke(15, 12, "typo", Actor.USER, "ron", ASOF)),
            config(), ASOF);
        assertEquals(List.of("first", "third"),
            revoked.commitmentNotes().stream().map(CommitmentNote::text).toList());

        Derivation bad = Derive.derive(List.of(), List.of(
            new Decision.NoteCommitment(2, "ghost", "nowhere", Actor.USER, "ron", ASOF)), config(), ASOF);
        assertTrue(bad.commitmentNotes().isEmpty());
        assertTrue(bad.ineffective().stream()
            .anyMatch(i -> i.action().equals(Action.NOTE_COMMITMENT.wire())));
    }

    @Test
    void settleOccurrenceClearsArrearsAndRevokeReturnsThem() {
        List<Fact> facts = acmeFacts();
        Decision declare = declare(10, "acme", "Acme", "out", 10000, LocalDate.of(2026, 1, 15), "ACME");
        Decision settle = new Decision.SettleOccurrence(20, "acme",
            List.of(LocalDate.of(2026, 7, 15), LocalDate.of(2026, 8, 15), LocalDate.of(2026, 9, 15)),
            "paid in cash", Actor.USER, "ron", ASOF);

        Derivation settled = Derive.derive(facts, List.of(declare, settle), config(), ASOF);
        assertEquals(0, commitment(settled, "acme").arrearsCount());
        assertTrue(item(settled, ReviewItem.COMMITMENT_ARREARS, "acme").isEmpty());
        assertEquals(CommitmentStatus.ACTIVE, commitment(settled, "acme").status(),
            "a settled occurrence is engagement, not silence");
        CommitmentOccurrence july = at(occurrences(settled, "acme"), LocalDate.of(2026, 7, 15));
        assertEquals(OccurrenceStatus.SETTLED, july.status());
        assertEquals(20L, july.settleN().longValue());

        Derivation revoked = Derive.derive(facts, List.of(declare, settle,
            new Decision.Revoke(21, 20, "not actually", Actor.USER, "ron", ASOF)), config(), ASOF);
        assertEquals(3, commitment(revoked, "acme").arrearsCount());
        assertEquals(OccurrenceStatus.MISSED,
            at(occurrences(revoked, "acme"), LocalDate.of(2026, 7, 15)).status());
        assertTrue(item(revoked, ReviewItem.COMMITMENT_ARREARS, "acme").isPresent());
    }

    @Test
    void aDismissedCandidateReopensWhenANewerFactLandsForTheSeries() {
        List<Fact> facts = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 4, 15);
        for (int i = 0; i < 6; i++) {
            facts.add(fact(i + 1, "spotify-" + (i + 1), "ing-orange", date, -1299, "SPOTIFY", null, 0));
            date = date.plusMonths(1);
        }
        Decision dismiss = new Decision.Dismiss(20, ReviewItem.SUSPECTED_RECURRING,
            List.of("SPOTIFY"), "keep an eye on it", Actor.USER, "ron", ASOF);

        Derivation silenced = Derive.derive(facts, List.of(dismiss), config(), ASOF);
        assertTrue(item(silenced, ReviewItem.SUSPECTED_RECURRING, "SPOTIFY").isEmpty(),
            silenced.review().toString());

        List<Fact> newer = new ArrayList<>(facts);
        newer.add(fact(21, "spotify-7", "ing-orange", LocalDate.of(2026, 9, 20), -1299, "SPOTIFY", null, 0));
        Derivation reopened = Derive.derive(newer, List.of(dismiss), config(), ASOF);
        assertTrue(item(reopened, ReviewItem.SUSPECTED_RECURRING, "SPOTIFY").isPresent(),
            "a newer fact for the series re-opens the question");

        Derivation typo = Derive.derive(facts, List.of(new Decision.Dismiss(22,
            ReviewItem.SUSPECTED_RECURRING, List.of("SPOTFIY"), "typo", Actor.USER, "ron", ASOF)),
            config(), ASOF);
        assertTrue(typo.ineffective().stream().anyMatch(i -> i.action().equals(Action.DISMISS.wire())));
    }

    @Test
    void aDismissedCommitmentItemReopensWhenTheCommitmentMatchesANewerFact() {
        List<Fact> facts = acmeFacts();
        Decision declare = declare(10, "acme", "Acme", "out", 10000, LocalDate.of(2026, 1, 15), "ACME");
        Decision dismiss = new Decision.Dismiss(20, ReviewItem.DORMANT_COMMITMENT, List.of("acme"),
            "keep tracking", Actor.USER, "ron", ASOF);

        Derivation silenced = Derive.derive(facts, List.of(declare, dismiss), config(), ASOF);
        assertTrue(item(silenced, ReviewItem.DORMANT_COMMITMENT, "acme").isEmpty());
        assertTrue(item(silenced, ReviewItem.COMMITMENT_ARREARS, "acme").isPresent(),
            "a different kind is not silenced by a DORMANT_COMMITMENT dismiss");

        List<Fact> paid = new ArrayList<>(facts);
        paid.add(fact(21, "acme-7", "ing-savings", LocalDate.of(2026, 7, 15), -10000, "ACME BILL", null, 0));
        Derivation reopened = Derive.derive(paid, List.of(declare, dismiss), config(), ASOF);
        assertTrue(item(reopened, ReviewItem.DORMANT_COMMITMENT, "acme").isPresent(),
            "a newer matched fact re-opens the question");

        Derivation typo = Derive.derive(facts, List.of(declare, new Decision.Dismiss(22,
            ReviewItem.COMMITMENT_ARREARS, List.of("ghost"), "typo", Actor.USER, "ron", ASOF)),
            config(), ASOF);
        assertTrue(typo.ineffective().stream().anyMatch(i -> i.action().equals(Action.DISMISS.wire())));
    }

    @Test
    void aCommitmentMatchedOnATransferLegDoesNotMoveWhenPairingChanges() {
        List<Fact> facts = List.of(
            fact(1, "loan-1", "ing-orange", LocalDate.of(2026, 7, 5), -250000, "HOME LOAN REPAYMENT 9876", null, 0),
            fact(2, "contra-1", "ing-savings", LocalDate.of(2026, 7, 5), 250000, "TRANSFER FROM OFFSET 9876", null, 0),
            fact(3, "loan-2", "ing-orange", LocalDate.of(2026, 8, 5), -250000, "HOME LOAN REPAYMENT 9876", null, 0),
            fact(4, "contra-2", "ing-savings", LocalDate.of(2026, 8, 5), 250000, "TRANSFER FROM OFFSET 9876", null, 0),
            fact(5, "loan-3", "ing-orange", LocalDate.of(2026, 9, 5), -250000, "HOME LOAN REPAYMENT 9876", null, 0),
            fact(6, "contra-3", "ing-savings", LocalDate.of(2026, 9, 5), 250000, "TRANSFER FROM OFFSET 9876", null, 0));
        Decision declare = declare(10, "home-loan", "Home loan", "out", 250000, LocalDate.of(2026, 7, 5),
            "HOME LOAN REPAYMENT");

        Derivation matched = Derive.derive(facts, List.of(declare), pairingConfig(true), ASOF);
        Derivation external = Derive.derive(facts, List.of(declare), pairingConfig(false), ASOF);

        assertEquals(LegState.MATCHED, matched.current("loan-1").orElseThrow().leg());
        assertEquals(3, matched.transfers().size());
        assertEquals(LegState.EXTERNAL, external.current("loan-1").orElseThrow().leg());
        assertTrue(external.transfers().isEmpty());
        assertEquals(occurrences(matched, "home-loan"), occurrences(external, "home-loan"),
            "the commitment saw core fields; a pairing change never moves it");
        assertEquals("loan-1",
            at(occurrences(matched, "home-loan"), LocalDate.of(2026, 7, 5)).matchedExternalId());
    }

    @Test
    void aCatchUpLumpClearsTheArrearsAndTheReviewItem() {
        List<Fact> facts = new ArrayList<>(acmeFacts());
        facts.add(fact(8, "lump", "ing-savings", LocalDate.of(2026, 9, 28), -30000, "ACME BILL", null, 0));

        Derivation d = Derive.derive(facts,
            List.of(declare(10, "acme", "Acme", "out", 10000, LocalDate.of(2026, 1, 15), "ACME")),
            config(), ASOF);
        assertEquals(0, commitment(d, "acme").arrearsCount(), "the backlog cleared from the front");
        assertTrue(item(d, ReviewItem.COMMITMENT_ARREARS, "acme").isEmpty());
        for (LocalDate due : List.of(LocalDate.of(2026, 7, 15), LocalDate.of(2026, 8, 15),
                LocalDate.of(2026, 9, 15))) {
            CommitmentOccurrence occurrence = at(occurrences(d, "acme"), due);
            assertEquals(OccurrenceStatus.OCCURRED, occurrence.status());
            assertEquals("lump", occurrence.matchedExternalId(), "one payment covered three periods");
        }
        assertEquals(CommitmentStatus.ACTIVE, commitment(d, "acme").status(),
            "the catch-up is engagement, not silence");
    }

    @Test
    void commitmentDerivationIsPureAndOrdered() {
        List<Fact> facts = acmeFacts();
        Decision declare = declare(10, "acme", "Acme", "out", 10000, LocalDate.of(2026, 1, 15), "ACME");
        Decision note = new Decision.NoteCommitment(11, "acme", "hello", Actor.USER, "ron", ASOF);
        Derivation first = Derive.derive(facts, List.of(declare, note), config(), ASOF);
        Derivation second = Derive.derive(facts, List.of(declare, note), config(), ASOF);
        assertEquals(first, second);

        List<String> ids = first.commitments().stream().map(Commitment::commitmentId).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
        List<CommitmentOccurrence> sorted = first.commitmentOccurrences().stream()
            .sorted(java.util.Comparator.comparing(CommitmentOccurrence::commitmentId)
                .thenComparing(CommitmentOccurrence::dueDate))
            .toList();
        assertEquals(sorted, first.commitmentOccurrences());
        assertEquals(1, first.commitmentNotes().size());
    }
}
