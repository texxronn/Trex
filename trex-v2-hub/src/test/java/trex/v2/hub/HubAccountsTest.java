package trex.v2.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Envelope;
import trex.v2.core.Fact;
import trex.v2.core.IngestEvent;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.hub.api.AccountsResponse;
import trex.v2.log.JsonlJournal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Accounts overview (V2-PROPOSAL.md §10.1, §10.5): all-time first/last, the window's weekly
 * buckets, holes inside the range, before/after outside it — and reading never touches the log.
 */
class HubAccountsTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void weeklyCoverageAcrossAccounts(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                ingest(1, IngestEvent.START, "b1", "savings.csv"),
                fact(2, "a", "ing-savings", LocalDate.of(2026, 8, 3)),
                fact(3, "b", "ing-savings", LocalDate.of(2026, 9, 21)),
                fact(4, "c", "ing-savings", LocalDate.of(2026, 9, 28)),
                ingest(5, IngestEvent.COMPLETE, "b1", "savings.csv"),
                fact(6, "d", "ing-orange", LocalDate.of(2026, 6, 1))));
        }
        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 4L);
            long head = hub.head().n();

            AccountsResponse r = hub.accounts("3m", "week", LocalDate.of(2026, 10, 1));
            assertEquals("3m", r.window());
            assertEquals("week", r.granularity());
            assertEquals(LocalDate.of(2026, 6, 29), r.from());
            assertEquals(LocalDate.of(2026, 10, 4), r.to());
            assertEquals(3, r.accounts().size());

            AccountsResponse.AccountCoverage savings = r.accounts().get(0);
            assertEquals("ing-savings", savings.ref());
            assertEquals(LocalDate.of(2026, 8, 3), savings.first());
            assertEquals(LocalDate.of(2026, 9, 28), savings.last());
            assertEquals(3, savings.txns());
            assertEquals(3, savings.txnsInWindow());
            assertEquals(6, savings.holes());
            assertEquals(14, savings.buckets().size());
            assertEquals("before", state(savings, "2026-W27"));
            assertEquals("facts", state(savings, "2026-W32"));
            assertEquals("hole", state(savings, "2026-W38"));
            assertEquals("facts", state(savings, "2026-W40"));
            assertEquals(List.of("savings.csv"), files(savings, "2026-W32"));
            assertEquals("savings.csv", savings.lastImport().file());
            assertEquals("ok", savings.lastImport().status());
            assertEquals(AT.toEpochMilli(), savings.lastImport().startedMs());

            AccountsResponse.AccountCoverage orange = r.accounts().get(1);
            assertEquals("ing-orange", orange.ref());
            assertEquals(LocalDate.of(2026, 6, 1), orange.first());
            assertEquals(1, orange.txns());
            assertEquals(0, orange.txnsInWindow());
            assertEquals(0, orange.holes());
            assertTrue(orange.buckets().stream().allMatch(b -> b.state().equals("after")));
            assertNull(orange.lastImport());

            AccountsResponse.AccountCoverage never = r.accounts().get(2);
            assertEquals("cba-netsaver", never.ref());
            assertNull(never.first());
            assertNull(never.last());
            assertEquals(0, never.txns());
            assertEquals(14, never.buckets().size());
            assertTrue(never.buckets().stream().allMatch(b -> b.state().equals("none")));

            assertThrows(IllegalArgumentException.class, () -> hub.accounts("5y", "week", LocalDate.of(2026, 10, 1)));
            assertThrows(IllegalArgumentException.class, () -> hub.accounts("12m", "day", LocalDate.of(2026, 10, 1)));

            // Reading the overview mirrors and derives nothing: the log head is untouched.
            assertEquals(head, hub.head().n());
        }
    }

    @Test
    void monthGrainUsesCalendarMonths(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "a", "ing-savings", LocalDate.of(2026, 8, 3)),
                fact(2, "b", "ing-savings", LocalDate.of(2026, 10, 5))));
        }
        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 2L);
            AccountsResponse r = hub.accounts("3m", "month", LocalDate.of(2026, 10, 1));
            assertEquals(LocalDate.of(2026, 7, 1), r.from());
            assertEquals(LocalDate.of(2026, 10, 31), r.to());
            AccountsResponse.AccountCoverage savings = r.accounts().get(0);
            assertEquals(4, savings.buckets().size());
            assertEquals("before", state(savings, "2026-07"));
            assertEquals("facts", state(savings, "2026-08"));
            assertEquals("hole", state(savings, "2026-09"));
            assertEquals("facts", state(savings, "2026-10"));
            assertEquals(1, savings.holes());
        }
    }

    @Test
    void ingestsCarryTheAccountFrontierClampedToToday(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        LocalDate old = LocalDate.of(2020, 1, 2);
        LocalDate future = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(30);
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "a", "ing-savings", old),
                fact(2, "b", "ing-savings", future),
                ingest(3, IngestEvent.START, "b1", "savings.csv"),
                ingest(4, IngestEvent.COMPLETE, "b1", "savings.csv")));
        }
        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 2L
                && !hub.ingests(null).rows().isEmpty());
            List<trex.v2.hub.api.IngestsResponse.IngestRow> rows = hub.ingests(null).rows();
            assertEquals(1, rows.size());
            assertEquals("ing-savings", rows.getFirst().accountRef());
            assertEquals(old, rows.getFirst().latestTxnDate(),
                "the frontier is MAX(date) clamped to today: a future fact does not count");
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static Fact fact(long n, String id, String account, LocalDate date) {
        return new Fact(n, id, account, date, -1000, 0, "COLES " + id, null, 0,
            Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", AT);
    }

    private static IngestEvent ingest(long n, String phase, String batch, String file) {
        boolean start = IngestEvent.START.equals(phase);
        return new IngestEvent(Envelope.stamped(n, IngestEvent.KIND, AT), phase, batch, "sha256:test",
            file, "ing-savings", "ing-csv", "ing-csv/1",
            start ? null : 3, start ? null : 0, start ? null : 0, start ? null : "ok");
    }

    private static String state(AccountsResponse.AccountCoverage account, String key) {
        return account.buckets().stream().filter(b -> b.key().equals(key)).findFirst()
            .orElseThrow(() -> new AssertionError("no bucket " + key)).state();
    }

    private static List<String> files(AccountsResponse.AccountCoverage account, String key) {
        return account.buckets().stream().filter(b -> b.key().equals(key)).findFirst()
            .orElseThrow(() -> new AssertionError("no bucket " + key)).files();
    }

    private static void config(Path configDir) throws Exception {
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
                currency: "AUD"
                balanceSource: statement
              - ref: "ing-orange"
                currency: "AUD"
                balanceSource: statement
              - ref: "cba-netsaver"
                currency: "AUD"
                balanceSource: statement
            """);
        Files.writeString(configDir.resolve("users.yaml"), """
            users:
              - id: "ron"
                name: "Ron"
                active: true
            """);
        Files.writeString(configDir.resolve("categories.yaml"), """
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when:
                  match: "COLES"
            """);
        Files.writeString(configDir.resolve("transfers.yaml"), """
            windowDays: 4
            allowlist:
              - 'Transfer'
            """);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within 10s");
    }
}
