package trex.egress.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.CanonicalEvent;
import trex.core.Confidence;
import trex.core.EventState;
import trex.core.Flag;
import trex.core.Provenance;
import trex.core.TypeHint;
import trex.sequencer.journal.JsonlJournal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SPEC §7 test 8 for the SQLite follower, plus the journal-mirror schema (§5.3). */
class SqliteFollowerTest {

    @TempDir
    Path dir;

    static CanonicalEvent line(long n) {
        return new CanonicalEvent(n, "ext" + n, "ing-savings", null, "AUD", LocalDate.parse("2026-06-01"), -100 * n,
            1000 - 100 * n, "row " + n, "row " + n, TypeHint.WITHDRAWAL, null, null, null, EventState.HELD, null,
            List.of(), Provenance.BANK, "test", null, null, null, null, null, null, Instant.parse("2026-06-02T00:00:00Z"));
    }

    static List<CanonicalEvent> lines(long fromN, long toN) {
        return LongStream.rangeClosed(fromN, toN).mapToObj(SqliteFollowerTest::line).toList();
    }

    private static List<Long> ns(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT n FROM journal ORDER BY n")) {
            List<Long> ns = new ArrayList<>();
            while (rs.next()) {
                ns.add(rs.getLong(1));
            }
            return ns;
        }
    }

    @Test
    void resumesAtPersistedOffsetWithoutGapOrDuplicate() throws Exception {
        Path journal = dir.resolve("journal.jsonl");
        Path db = dir.resolve("mirror.db");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(lines(1, 4));
        }
        byte[] next = JsonlJournal.serialize(lines(5, 5));
        Files.write(journal, Arrays.copyOf(next, 30), StandardOpenOption.APPEND);

        try (SqliteFollower f = new SqliteFollower(journal, db)) {
            assertEquals(4, f.pass());
            assertEquals(JsonlJournal.serialize(lines(1, 4)).length, f.readOffset());
        }

        Files.write(journal, Arrays.copyOfRange(next, 30, next.length), StandardOpenOption.APPEND);
        Files.write(journal, JsonlJournal.serialize(lines(6, 8)), StandardOpenOption.APPEND);
        try (SqliteFollower f = new SqliteFollower(journal, db)) {
            assertEquals(4, f.pass());
            assertEquals(0, f.pass());
            assertEquals(Files.size(journal), f.readOffset());
        }
        assertEquals(LongStream.rangeClosed(1, 8).boxed().toList(), ns(db));
    }

    @Test
    void followWakesOnJournalChangeWithoutWaitingForFallback() throws Exception {
        Path journal = dir.resolve("journal.jsonl");
        Path db = dir.resolve("mirror.db");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(lines(1, 2));
        }
        java.util.concurrent.atomic.AtomicInteger mirrored = new java.util.concurrent.atomic.AtomicInteger();
        try (SqliteFollower f = new SqliteFollower(journal, db)) {
            Thread follower = Thread.ofVirtual().start(() -> {
                try {
                    f.follow(60_000, mirrored::addAndGet);
                } catch (InterruptedException | java.sql.SQLException e) {
                    // stopped
                }
            });
            try {
                long deadline = System.currentTimeMillis() + 5_000;
                while (mirrored.get() < 2 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                Files.write(journal, JsonlJournal.serialize(lines(3, 5)), StandardOpenOption.APPEND);
                deadline = System.currentTimeMillis() + 5_000;
                while (mirrored.get() < 5 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                assertEquals(5, mirrored.get(), "woken by the change event, far before the 60 s fallback");
            } finally {
                follower.interrupt();
                follower.join(5_000);
            }
        }
        assertEquals(LongStream.rangeClosed(1, 5).boxed().toList(), ns(db));
    }

    @Test
    void redeliveryIsANoOp() throws Exception {
        Path journal = dir.resolve("journal.jsonl");
        Path db = dir.resolve("mirror.db");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(lines(1, 3));
        }
        try (SqliteFollower f = new SqliteFollower(journal, db)) {
            f.pass();
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE follower_state SET offset = 0");
        }
        try (SqliteFollower f = new SqliteFollower(journal, db)) {
            assertEquals(3, f.pass());
        }
        assertEquals(List.of(1L, 2L, 3L), ns(db));
    }

    @Test
    void storesEveryFieldOneRowPerLine() throws Exception {
        Path journal = dir.resolve("journal.jsonl");
        Path db = dir.resolve("mirror.db");
        CanonicalEvent leg = line(1);
        CanonicalEvent transfer = new CanonicalEvent(3, "TRF-abc", "ing-savings", "cba-everyday", "AUD",
            LocalDate.parse("2026-06-01"), 100, 0, "row 1", "row 1", TypeHint.TRANSFER, "TRF-abc", List.of("ext1", "ext2"),
            null, EventState.MATCHED, Confidence.HIGH, List.of(), Provenance.AUTHORED, "test", null, null, null, 12345L,
            "USD", "manual", Instant.parse("2026-06-02T00:00:00.123456Z"));
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(leg, leg.reappend(2, EventState.MATCHED, List.of(Flag.POTENTIAL_DUP), null), transfer));
        }
        try (SqliteFollower f = new SqliteFollower(journal, db)) {
            f.pass();
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                rs.next();
                assertEquals("wal", rs.getString(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*), count(DISTINCT external_id) FROM journal")) {
                rs.next();
                assertEquals(3, rs.getInt(1));
                assertEquals(2, rs.getInt(2));
            }
            try (ResultSet rs = st.executeQuery("SELECT flags, state FROM journal WHERE n = 2")) {
                rs.next();
                assertEquals("[\"POTENTIAL_DUP\"]", rs.getString(1));
                assertEquals("MATCHED", rs.getString(2));
            }
            try (ResultSet rs = st.executeQuery("""
                SELECT to_account_ref, leg_ids, typeof(amount), typeof(balance), confidence, provenance,
                       foreign_amount, foreign_currency, comment, ingested_at, date, flags
                FROM journal WHERE n = 3""")) {
                rs.next();
                assertEquals("cba-everyday", rs.getString(1));
                assertEquals("[\"ext1\",\"ext2\"]", rs.getString(2));
                assertEquals("integer", rs.getString(3));
                assertEquals("integer", rs.getString(4));
                assertEquals("HIGH", rs.getString(5));
                assertEquals("AUTHORED", rs.getString(6));
                assertEquals(12345L, rs.getLong(7));
                assertEquals("USD", rs.getString(8));
                assertEquals("manual", rs.getString(9));
                assertEquals("2026-06-02T00:00:00.123456Z", rs.getString(10));
                assertEquals("2026-06-01", rs.getString(11));
                assertEquals("[]", rs.getString(12));
            }
        }
    }
}
