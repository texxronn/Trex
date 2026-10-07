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

    private static Fact fact(long n, String id, String account, LocalDate date, long amount, String raw) {
        return new Fact(n, id, account, date, amount, 0, raw, null, 0, Observation.POSTED, "test",
            Provenance.BANK, null, "test/1", Instant.parse("2026-09-30T00:00:00Z"));
    }

    private static Path journal(Path dir) {
        Path path = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(path)) {
            j.appendBatch(List.of(
                fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234"),
                fact(2, "b", "ing-orange", LocalDate.of(2026, 9, 1), 1000, "Transfer to Savings 4321"),
                fact(3, "c", "ing-savings", LocalDate.of(2026, 9, 1), -1000, "Transfer from Savings 4321"),
                new Decision.Pin(4, List.of("a"), "GROCERIES", "pin", Actor.USER, "ron", ASOF)));
        }
        return path;
    }

    @Test
    void applyMaterialisesAndIsIncremental(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        try (Indexer indexer = Indexer.open(db, config())) {
            assertTrue(indexer.apply(journal, ASOF));
            assertFalse(indexer.apply(journal, ASOF), "a second apply with nothing new must be a no-op");
            assertEquals(3, count(db, "txn_current"));
            assertEquals(1, count(db, "transfer"));
            assertEquals(1, count(db, "pin_current"));
            assertEquals("GROCERIES", string(db, "SELECT category FROM txn_current WHERE external_id='a'"));
        }
    }

    @Test
    void rebuildEqualsIncremental(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        try (Indexer indexer = Indexer.open(db, config())) {
            indexer.apply(journal, ASOF);
            String incremental = indexer.derivedFingerprint();
            indexer.rebuild(journal, ASOF);
            assertEquals(incremental, indexer.derivedFingerprint());
        }
    }

    @Test
    void rebuildPicksUpLinesAddedAfterTheFirstApply(@TempDir Path dir) {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        try (Indexer indexer = Indexer.open(db, config())) {
            indexer.apply(journal, ASOF);
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(fact(5, "d", "ing-savings", LocalDate.of(2026, 9, 2), -2000, "COLES 9999")));
            }
            assertTrue(indexer.apply(journal, ASOF), "a new line must trigger a re-derive");
            assertEquals(4, count(db, "txn_current"));
        }
    }

    @Test
    void aReplacedJournalIsDetectedNotJustAShrink(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("trex.sqlite");
        Path journal = journal(dir);
        try (Indexer indexer = Indexer.open(db, config())) {
            assertTrue(indexer.apply(journal, ASOF));
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

    // ---- tiny read helpers ------------------------------------------------------------------

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
