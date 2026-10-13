package trex.v2.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.TransferRules;
import trex.v2.core.config.User;
import trex.v2.core.derive.AmountKind;
import trex.v2.core.derive.Cadence;
import trex.v2.core.derive.CommitmentKind;
import trex.v2.core.derive.ReviewItem;
import trex.v2.log.JsonlJournal;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The §4 rebuild test and §15.3 rebuild ≡ incremental: the same log, the same {@code asOf},
 * identical derived tables whether they were reached incrementally or from a wipe.
 */
class IndexerTest {

    private static final Instant ASOF = Instant.parse("2026-10-01T00:00:00Z");

    private static DeriveConfig config() {
        List<Account> accounts = List.of(
            new Account("ing-savings", "AUD", BalanceSource.STATEMENT, 7),
            new Account("ing-orange", "AUD", BalanceSource.STATEMENT, 7));
        Map<String, Account> accountMap = accounts.stream().collect(Collectors.toMap(Account::ref, a -> a));
        Map<String, User> userMap = Map.of("ron", new User("ron", "Ron", true, "weekly"));
        Registry registry = new Registry(accountMap, userMap);
        RuleSet rules = RuleSet.compile("t.yaml", new RuleSet.File(
            List.of("GROCERIES"),
            List.of(new RuleSet.RuleEntry("GROCERIES", null, new RuleSet.WhenEntry(null, null, null, null,
                "COLES", "raw", null, null, null, null)))));
        return new DeriveConfig(registry, rules,
            TransferRules.defaults(List.of("Transfer")), trex.v2.core.config.Profiles.empty(), "sha256:cfg");
    }

    /** A second declared category, DINING, so a pin can differ from the rule's GROCERIES answer. */
    private static DeriveConfig pinConfig() {
        List<Account> accounts = List.of(
            new Account("ing-savings", "AUD", BalanceSource.STATEMENT, 7),
            new Account("ing-orange", "AUD", BalanceSource.STATEMENT, 7));
        Map<String, Account> accountMap = accounts.stream().collect(Collectors.toMap(Account::ref, a -> a));
        Map<String, User> userMap = Map.of("ron", new User("ron", "Ron", true, "weekly"));
        Registry registry = new Registry(accountMap, userMap);
        RuleSet rules = RuleSet.compile("t.yaml", new RuleSet.File(
            List.of("GROCERIES", "DINING"),
            List.of(new RuleSet.RuleEntry("GROCERIES", null, new RuleSet.WhenEntry(null, null, null, null,
                "COLES", "raw", null, null, null, null)))));
        return new DeriveConfig(registry, rules,
            TransferRules.defaults(List.of("Transfer")), trex.v2.core.config.Profiles.empty(), "sha256:cfg");
    }

    private static Decision.Pin pin(long n, String id, String category) {
        return new Decision.Pin(n, List.of(id), category, "pin", Actor.USER, "ron", ASOF);
    }

    private static Decision.Unpin unpin(long n, String id) {
        return new Decision.Unpin(n, List.of(id), "unpin", Actor.USER, "ron", ASOF);
    }

    private static Fact fact(long n, String id, String account, LocalDate date, long amount, String raw) {
        return new Fact(n, id, account, date, amount, 0, raw, null, 0, Observation.POSTED, "test",
            Provenance.BANK, null, "test/1", Instant.parse("2026-09-30T00:00:00Z"));
    }

    private static Decision.UserAck ack(long n, String id) {
        return new Decision.UserAck(n, id, "sha256:cfg", DeriveConfig.DERIVE_VERSION,
            DeriveConfig.HASH_VERSION, "state:abc", null, Actor.USER, "ron", ASOF);
    }

    private static Path journal(Path dir) {
        Path path = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(path)) {
            j.appendBatch(List.of(
                fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234"),
                fact(2, "b", "ing-orange", LocalDate.of(2026, 9, 1), 1000, "Transfer to Savings 4321"),
                fact(3, "c", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "Transfer from Savings 4321"),
                new Decision.Pin(4, List.of("a"), "GROCERIES", "pin", Actor.USER, "ron", ASOF),
                new Decision.Note(5, "a", "check me", Actor.USER, "ron", ASOF)));
        }
        return path;
    }

    /**
     * Two identical posted charges on one account and day, distinct ids: a POTENTIAL_DUP (and
     * RESTATEMENT) cluster whose subject is the smallest member id, "d1". Enough to raise a real
     * review item so a DISMISS has something to silence.
     */
    private static Path duplicateJournal(Path dir) {
        Path path = dir.resolve("duplicates.jsonl");
        try (JsonlJournal j = new JsonlJournal(path)) {
            j.appendBatch(List.of(
                fact(1, "d1", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "DUPLICATE CHARGE"),
                fact(2, "d2", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "DUPLICATE CHARGE")));
        }
        return path;
    }

    @Test
    void applyMaterialisesAndIsIncremental(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        try (Indexer indexer = Indexer.open(db, config())) {
            assertTrue(applyChanged(indexer, journal, ASOF));
            assertFalse(applyChanged(indexer, journal, ASOF), "a second apply with nothing new must be a no-op");
            assertEquals(3, count(db, "txn_current"));
            assertEquals(1, count(db, "transfer"));
            assertEquals(1, count(db, "pin_current"));
            assertEquals(1, count(db, "note_current"));
            assertEquals("check me", string(db, "SELECT text FROM note_current WHERE external_id='a'"));
            assertEquals("GROCERIES", string(db, "SELECT category FROM txn_current WHERE external_id='a'"));
            assertEquals("transaction", string(db, "SELECT role FROM txn_current WHERE external_id='a'"));
        }
    }

    @Test
    void applyAtANewAsOfWithNothingNewIsANoOpAndDeriveNowAdvancesIt(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        Instant asof2 = ASOF.plus(java.time.Duration.ofDays(30));
        try (Indexer indexer = Indexer.open(db, config())) {
            assertTrue(applyChanged(indexer, journal, ASOF));
            String before = indexer.derivedFingerprint();
            // The write clock and the time clock are decoupled (V2-INCREMENTAL-REFRESH-PLAN.md §2.3):
            // a consistent apply with no new lines does not re-derive, even at a later asOf.
            assertFalse(applyChanged(indexer, journal, asof2), "no new lines must not re-derive at a new asOf");
            assertEquals(before, indexer.derivedFingerprint(), "a no-op apply leaves the tables untouched");
            assertEquals(ASOF.toString(), indexer.meta("as_of").orElse(null));
            // The timer's entry point forces the full derive and reports the advance.
            assertFalse(indexer.deriveNow(ASOF), "deriveNow at the stored asOf changes nothing");
            assertEquals(ASOF.toString(), indexer.meta("as_of").orElse(null));
            assertTrue(indexer.deriveNow(asof2), "deriveNow advances as_of");
            assertEquals(asof2.toString(), indexer.meta("as_of").orElse(null));
        }
    }

    @Test
    void anAccountProfileIsWrittenAsTheRowRoleAndSurvivesARebuild(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        DeriveConfig withProfile = new DeriveConfig(config().registry(), config().categories(),
            config().transfers(), new trex.v2.core.config.Profiles(List.of(
                new trex.v2.core.config.Profiles.Rule("ing-savings",
                    java.util.regex.Pattern.compile("COLES", java.util.regex.Pattern.CASE_INSENSITIVE),
                    "reference"))),
            "sha256:cfg");
        try (Indexer indexer = Indexer.open(db, withProfile)) {
            applyChanged(indexer, journal, ASOF);
            assertEquals("noop", string(db, "SELECT role FROM txn_current WHERE external_id='a'"));
            assertEquals("transaction", string(db, "SELECT role FROM txn_current WHERE external_id='b'"));
            // The role-filtered reads verify() and the hub run over: noops are exclusions, not postings.
            assertEquals(1, indexer.currentNoops().size());
            assertEquals("a", indexer.currentNoops().getFirst().externalId());
            assertTrue(indexer.currentTransactions().stream().noneMatch(f -> f.externalId().equals("a")));
            indexer.rebuild(journal, ASOF);
            assertEquals("noop", string(db, "SELECT role FROM txn_current WHERE external_id='a'"));
        }
    }

    @Test
    void rebuildEqualsIncremental(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        try (Indexer indexer = Indexer.open(db, config())) {
            applyChanged(indexer, journal, ASOF);
            String incremental = indexer.derivedFingerprint();
            indexer.rebuild(journal, ASOF);
            assertEquals(incremental, indexer.derivedFingerprint());
        }
    }

    @Test
    void aRowScopedAckPatchesAndEqualsARebuild(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        try (Indexer indexer = Indexer.open(db, config())) {
            applyChanged(indexer, journal, ASOF);
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(ack(6, "a")));
            }
            assertTrue(applyChanged(indexer, journal, ASOF));
            assertEquals(1, count(db, "user_ack"));
            // The patch path leaves as_of put; a full derive would have rewritten it identically
            // here, so the fingerprint is the load-bearing check.
            String incremental = indexer.derivedFingerprint();
            indexer.rebuild(journal, ASOF);
            assertEquals(incremental, indexer.derivedFingerprint(),
                "a row-scoped ACK must give the same tables as a full rebuild");
        }
    }

    @Test
    void aRowScopedAckAtADifferentAsOfDoesNotAdvanceIt(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        Instant asof2 = ASOF.plus(java.time.Duration.ofDays(30));
        try (Indexer indexer = Indexer.open(db, config())) {
            applyChanged(indexer, journal, ASOF);
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(ack(6, "a")));
            }
            assertTrue(applyChanged(indexer, journal, asof2));
            // A full derive would advance as_of to asof2; the patch is asOf-independent and does not.
            assertEquals(ASOF.toString(), indexer.meta("as_of").orElse(null));
            assertEquals(1, count(db, "user_ack"));
            assertEquals("a", string(db, "SELECT external_id FROM user_ack WHERE user_id='ron'"));
        }
    }

    @Test
    void unackRoundTripsToThePreAckFingerprint(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        try (Indexer indexer = Indexer.open(db, config())) {
            applyChanged(indexer, journal, ASOF);
            String before = indexer.derivedFingerprint();
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(ack(6, "a")));
            }
            applyChanged(indexer, journal, ASOF);
            assertEquals(1, count(db, "user_ack"));
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(new Decision.UserUnack(7, "a", null, Actor.USER, "ron", ASOF)));
            }
            applyChanged(indexer, journal, ASOF);
            assertEquals(0, count(db, "user_ack"));
            assertEquals(before, indexer.derivedFingerprint(),
                "ACK then UNACK returns to the pre-ACK derived state");
        }
    }

    @Test
    void aMixedAppendFallsBackToAFullDerive(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        Instant asof2 = ASOF.plus(java.time.Duration.ofDays(30));
        try (Indexer indexer = Indexer.open(db, config())) {
            applyChanged(indexer, journal, ASOF);
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(
                    ack(6, "a"),
                    new Decision.Supersede(7, "a", "b", "reparse", Actor.USER, "ron", ASOF)));
            }
            assertTrue(applyChanged(indexer, journal, asof2));
            // A structural line in the batch cannot be patched, so the full derive advances as_of.
            assertEquals(asof2.toString(), indexer.meta("as_of").orElse(null));
        }
    }

    @Test
    void aRowScopedPinPatchesAndEqualsARebuild(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        try (Indexer indexer = Indexer.open(db, pinConfig())) {
            applyChanged(indexer, journal, ASOF);
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(pin(6, "a", "DINING")));
            }
            assertTrue(applyChanged(indexer, journal, ASOF));
            assertEquals("DINING", string(db, "SELECT category FROM txn_current WHERE external_id='a'"));
            assertEquals("PIN", string(db, "SELECT category_origin FROM txn_current WHERE external_id='a'"));
            assertEquals("DINING", string(db, "SELECT category FROM category_current WHERE external_id='a'"));
            assertEquals("DINING", string(db, "SELECT category FROM unit WHERE unit_id='a'"));
            assertEquals("DINING", string(db, "SELECT category FROM pin_current WHERE external_id='a'"));
            String incremental = indexer.derivedFingerprint();
            indexer.rebuild(journal, ASOF);
            assertEquals(incremental, indexer.derivedFingerprint(),
                "a row-scoped PIN must give the same tables as a full rebuild");
        }
    }

    @Test
    void pinThenUnpinEqualsARebuildAndRemovesThePinRow(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        try (Indexer indexer = Indexer.open(db, pinConfig())) {
            applyChanged(indexer, journal, ASOF);
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(pin(6, "a", "DINING")));
            }
            assertTrue(applyChanged(indexer, journal, ASOF));
            assertEquals("DINING", string(db, "SELECT category FROM txn_current WHERE external_id='a'"));
            assertEquals("DINING", string(db, "SELECT category FROM pin_current WHERE external_id='a'"));
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(unpin(7, "a")));
            }
            assertTrue(applyChanged(indexer, journal, ASOF));
            // The latest effective PIN/UNPIN wins: the UNPIN reverts the category and removes the
            // pin_current row, exactly as derive's pins() now folds both (V2-INCREMENTAL-REFRESH-PLAN.md §7).
            assertEquals("GROCERIES", string(db, "SELECT category FROM txn_current WHERE external_id='a'"));
            assertEquals("RULE", string(db, "SELECT category_origin FROM txn_current WHERE external_id='a'"));
            assertEquals("GROCERIES", string(db, "SELECT category FROM unit WHERE unit_id='a'"));
            assertEquals(0, count(db, "pin_current WHERE external_id='a'"));
            String incremental = indexer.derivedFingerprint();
            indexer.rebuild(journal, ASOF);
            assertEquals(incremental, indexer.derivedFingerprint(),
                "a PIN->UNPIN patch must give the same tables as a full rebuild");
        }
    }

    @Test
    void aRowScopedPinAtADifferentAsOfDoesNotAdvanceIt(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        Instant asof2 = ASOF.plus(java.time.Duration.ofDays(30));
        try (Indexer indexer = Indexer.open(db, pinConfig())) {
            applyChanged(indexer, journal, ASOF);
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(pin(6, "a", "DINING")));
            }
            assertTrue(applyChanged(indexer, journal, asof2));
            // A full derive would advance as_of to asof2; the patch is asOf-independent and does not.
            assertEquals(ASOF.toString(), indexer.meta("as_of").orElse(null));
            assertEquals("DINING", string(db, "SELECT category FROM txn_current WHERE external_id='a'"));
            String incremental = indexer.derivedFingerprint();
            indexer.rebuild(journal, ASOF);
            assertEquals(incremental, indexer.derivedFingerprint());
        }
    }

    @Test
    void aPinNamingAMatchedLegFallsBackToAFullDerive(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        Instant asof2 = ASOF.plus(java.time.Duration.ofDays(30));
        try (Indexer indexer = Indexer.open(db, config())) {
            applyChanged(indexer, journal, ASOF);
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(pin(6, "b", "GROCERIES")));
            }
            assertTrue(applyChanged(indexer, journal, asof2));
            // The matched leg cannot be patched, so the full derive ran and advanced as_of.
            assertEquals(asof2.toString(), indexer.meta("as_of").orElse(null));
            assertEquals(1, count(db, "ineffective_decision WHERE decision_n=6"));
        }
    }

    @Test
    void aPinNamingAnUnknownIdFallsBackToAFullDerive(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        Instant asof2 = ASOF.plus(java.time.Duration.ofDays(30));
        try (Indexer indexer = Indexer.open(db, config())) {
            applyChanged(indexer, journal, ASOF);
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(pin(6, "nope", "GROCERIES")));
            }
            assertTrue(applyChanged(indexer, journal, asof2));
            assertEquals(asof2.toString(), indexer.meta("as_of").orElse(null));
            assertEquals(1, count(db, "ineffective_decision WHERE decision_n=6"));
        }
    }

    @Test
    void aPinToAnUndeclaredCategoryFallsBackToAFullDerive(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        Instant asof2 = ASOF.plus(java.time.Duration.ofDays(30));
        try (Indexer indexer = Indexer.open(db, config())) {
            applyChanged(indexer, journal, ASOF);
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(pin(6, "a", "NOT_DECLARED")));
            }
            assertTrue(applyChanged(indexer, journal, asof2));
            assertEquals(asof2.toString(), indexer.meta("as_of").orElse(null));
            assertEquals(1, count(db, "ineffective_decision WHERE decision_n=6"));
        }
    }

    @Test
    void rebuildPicksUpLinesAddedAfterTheFirstApply(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        try (Indexer indexer = Indexer.open(db, config())) {
            applyChanged(indexer, journal, ASOF);
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(fact(5, "d", "ing-savings", LocalDate.of(2026, 9, 2), -2000, "COLES 9999")));
            }
            assertTrue(applyChanged(indexer, journal, ASOF), "a new line must trigger a re-derive");
            assertEquals(4, count(db, "txn_current"));
        }
    }

    @Test
    void aReplacedJournalIsDetectedNotJustAShrink(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        try (Indexer indexer = Indexer.open(db, config())) {
            assertTrue(applyChanged(indexer, journal, ASOF));
            assertFalse(indexer.journalPrefixReplaced(journal), "an appended journal is intact");

            // The same bytes, one byte of the last line changed: a different file of the same size.
            // The offset still lands inside it, so only the tail fingerprint can notice.
            byte[] original = java.nio.file.Files.readAllBytes(journal);
            byte[] replaced = original.clone();
            replaced[replaced.length - 2] = (byte) (replaced[replaced.length - 2] ^ 0x20);
            java.nio.file.Files.write(journal, replaced);

            assertTrue(indexer.journalPrefixReplaced(journal));
        }
    }

    @Test
    void indexLockRefusesASecondWriter(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        try (IndexLock first = IndexLock.acquire(db)) {
            assertThrows(IllegalStateException.class, () -> IndexLock.acquire(db));
        }
        try (IndexLock again = IndexLock.acquire(db)) {
            assertTrue(true);
        }
    }

    @Test
    void theCommitmentTablesPopulateAndSurviveARebuild(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = dir.resolve("commitments.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "acme-1", "ing-savings", LocalDate.of(2026, 8, 15), -10000, "ACME BILL"),
                fact(2, "acme-2", "ing-savings", LocalDate.of(2026, 9, 15), -10000, "ACME BILL"),
                new Decision.DeclareCommitment(3, "acme", "Acme", "out", Cadence.MONTHLY,
                    AmountKind.FIXED, CommitmentKind.BILL, List.of(new Decision.Match("ACME", null)),
                    10000L, LocalDate.of(2026, 8, 15), null, null, Actor.USER, "ron", ASOF),
                new Decision.NoteCommitment(4, "acme", "check the price", Actor.USER, "ron", ASOF)));
        }
        try (Indexer indexer = Indexer.open(db, config())) {
            assertTrue(applyChanged(indexer, journal, ASOF));
            assertEquals(1, count(db, "commitment"));
            assertEquals(1, count(db, "commitment_rule"));
            assertTrue(count(db, "commitment_occurrence") >= 2);
            assertEquals(1, count(db, "commitment_note"));
            assertEquals(1L, indexer.counts().get("commitment"));
            assertEquals("declared", string(db, "SELECT origin FROM commitment WHERE commitment_id='acme'"));
            assertEquals("monthly", string(db, "SELECT cadence FROM commitment WHERE commitment_id='acme'"));
            assertEquals("ACME", string(db, "SELECT match FROM commitment_rule WHERE commitment_id='acme'"));
            assertEquals("check the price",
                string(db, "SELECT text FROM commitment_note WHERE commitment_id='acme'"));
            assertEquals("occurred",
                string(db, "SELECT status FROM commitment_occurrence WHERE commitment_id='acme'"
                    + " AND due_date='2026-09-15'"));

            String incremental = indexer.derivedFingerprint();
            indexer.rebuild(journal, ASOF);
            assertEquals(incremental, indexer.derivedFingerprint());
            assertEquals(1, count(db, "commitment"));
            assertEquals(1, count(db, "commitment_note"));
        }
    }

    @Test
    void aRowScopedNotePatchesAndEqualsARebuild(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        Instant asof2 = ASOF.plus(java.time.Duration.ofDays(30));
        try (Indexer indexer = Indexer.open(db, config())) {
            applyChanged(indexer, journal, ASOF);
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(new Decision.Note(6, "a", "second note", Actor.USER, "ron", ASOF)));
            }
            assertTrue(applyChanged(indexer, journal, asof2));
            assertEquals(2, count(db, "note_current"));
            assertEquals("second note", string(db, "SELECT text FROM note_current WHERE decision_n=6"));
            assertEquals("a", string(db, "SELECT external_id FROM note_current WHERE decision_n=6"));
            // The patch is asOf-independent: it leaves as_of put while a full derive would advance it.
            assertEquals(ASOF.toString(), indexer.meta("as_of").orElse(null));
            String incremental = indexer.derivedFingerprint();
            indexer.rebuild(journal, ASOF);
            assertEquals(incremental, indexer.derivedFingerprint(),
                "a row-scoped NOTE must give the same tables as a full rebuild");
        }
    }

    @Test
    void aNoteNamingAnUnknownIdFallsBackToAFullDerive(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        Instant asof2 = ASOF.plus(java.time.Duration.ofDays(30));
        try (Indexer indexer = Indexer.open(db, config())) {
            applyChanged(indexer, journal, ASOF);
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(new Decision.Note(6, "nope", "orphan", Actor.USER, "ron", ASOF)));
            }
            assertTrue(applyChanged(indexer, journal, asof2));
            // The unknown id is derive's to mark ineffective, so the full derive ran and moved as_of.
            assertEquals(asof2.toString(), indexer.meta("as_of").orElse(null));
            assertEquals(1, count(db, "ineffective_decision WHERE decision_n=6"));
        }
    }

    @Test
    void aBatchContainingARevokeFallsBackToAFullDerive(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        Instant asof2 = ASOF.plus(java.time.Duration.ofDays(30));
        try (Indexer indexer = Indexer.open(db, config())) {
            applyChanged(indexer, journal, ASOF);
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(
                    new Decision.Note(6, "a", "still here", Actor.USER, "ron", ASOF),
                    new Decision.Revoke(7, 5, "undo the first note", Actor.USER, "ron", ASOF)));
            }
            assertTrue(applyChanged(indexer, journal, asof2));
            // A REVOKE is global; the batch cannot be patched, so the full derive ran and moved as_of.
            assertEquals(asof2.toString(), indexer.meta("as_of").orElse(null));
            assertEquals(0, count(db, "note_current WHERE decision_n=5"));
            assertEquals(1, count(db, "note_current WHERE decision_n=6"));
        }
    }

    @Test
    void aRowScopedDismissPatchesAndEqualsARebuild(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = duplicateJournal(dir);
        Instant asof2 = ASOF.plus(java.time.Duration.ofDays(30));
        try (Indexer indexer = Indexer.open(db, config())) {
            applyChanged(indexer, journal, ASOF);
            // Two identical charges on one day form a POTENTIAL_DUP cluster, subject the smallest id.
            assertEquals(1, count(db, "review_item WHERE kind='POTENTIAL_DUP' AND subject='d1'"));
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(new Decision.Dismiss(3, ReviewItem.POTENTIAL_DUP,
                    List.of("d1"), "a genuine repeat", Actor.USER, "ron", ASOF)));
            }
            assertTrue(applyChanged(indexer, journal, asof2));
            assertEquals(0, count(db, "review_item WHERE kind='POTENTIAL_DUP' AND subject='d1'"));
            // The patch leaves as_of put; a full derive would have advanced it.
            assertEquals(ASOF.toString(), indexer.meta("as_of").orElse(null));
            String incremental = indexer.derivedFingerprint();
            indexer.rebuild(journal, ASOF);
            assertEquals(incremental, indexer.derivedFingerprint(),
                "a row-scoped DISMISS must give the same tables as a full rebuild");
        }
    }

    @Test
    void aDismissNamingAnUnknownIdFallsBackToAFullDerive(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = duplicateJournal(dir);
        Instant asof2 = ASOF.plus(java.time.Duration.ofDays(30));
        try (Indexer indexer = Indexer.open(db, config())) {
            applyChanged(indexer, journal, ASOF);
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(new Decision.Dismiss(3, ReviewItem.POTENTIAL_DUP,
                    List.of("nope"), "not a fact", Actor.USER, "ron", ASOF)));
            }
            assertTrue(applyChanged(indexer, journal, asof2));
            // Derive emits an ineffective_decision the patch does not reproduce, so it ran in full.
            assertEquals(asof2.toString(), indexer.meta("as_of").orElse(null));
            assertEquals(1, count(db, "ineffective_decision WHERE decision_n=3"));
            assertEquals(1, count(db, "review_item WHERE kind='POTENTIAL_DUP' AND subject='d1'"));
        }
    }

    @Test
    void aRowScopedPatchReportsTheIdsItChanged(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        try (Indexer indexer = Indexer.open(db, config())) {
            assertTrue(indexer.apply(journal, ASOF).changed(), "the first apply is a full derive");
            assertTrue(indexer.apply(journal, ASOF).patchedIds().isEmpty(),
                "a full derive reports no ids");
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(ack(6, "a")));
            }
            ApplyResult patched = indexer.apply(journal, ASOF);
            assertTrue(patched.changed());
            assertEquals(java.util.Set.of("a"), patched.patchedIds(),
                "an ACK patch reports the row it changed");
            // A second apply with nothing new is a no-op and reports no ids.
            assertFalse(indexer.apply(journal, ASOF).changed());
        }
    }

    // ---- tiny read helpers ------------------------------------------------------------------

    /**
     * The old bare-boolean contract of {@link Indexer#apply}: whether the derived state moved.
     * {@code apply} now returns an {@link ApplyResult} so the hub can carry the patched ids; the
     * rebuild-equivalence tests only care that something moved.
     */
    private static boolean applyChanged(Indexer indexer, Path journal, Instant asOf) {
        return indexer.apply(journal, asOf).changed();
    }

    private static long count(Path db, String table) {
        return Long.parseLong(string(db, "SELECT COUNT(*) FROM " + table));
    }

    private static String string(Path db, String sql) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
