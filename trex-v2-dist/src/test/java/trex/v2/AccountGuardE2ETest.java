package trex.v2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import trex.v2.core.Fact;
import trex.v2.core.IngestEvent;
import trex.v2.core.LogLine;
import trex.v2.log.FramedReader;
import trex.v2.log.Json;
import trex.v2.sequencer.SequencerService;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The account guard through the real CLI and sequencer (V2-ACCOUNT-GUARD-PLAN.md): a file that
 * overlaps one account's rows is refused under another, appended once the guard is off, and
 * {@code --identify} names the right account without touching the log.
 */
class AccountGuardE2ETest {

    private static final String FIRST = """
        Date,Description,Credit,Debit,Balance
        01/07/2026,"COLES 1234",,-10.00,990.00
        02/07/2026,"OTHER SHOP",,-20.00,970.00
        03/07/2026,"CAFE",,-5.00,965.00
        """;

    private static final String SECOND = """
        Date,Description,Credit,Debit,Balance
        02/07/2026,"OTHER SHOP",,-20.00,970.00
        03/07/2026,"CAFE",,-5.00,965.00
        04/07/2026,"BAKERY",,-1.00,964.00
        """;

    @Test
    void aFileUnderTheWrongAccountIsRefusedUntilTheGuardIsOff(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        Path first = Files.writeString(dir.resolve("first.csv"), FIRST);
        Path second = Files.writeString(dir.resolve("second.csv"), SECOND);

        try (SequencerService sequencer = SequencerService.start(journal, configDir, "127.0.0.1", 0, Clock.systemUTC())) {
            String url = "http://127.0.0.1:" + sequencer.port();
            assertEquals(0, ingest(dir, url, journal, first, "ing-orange").exit());
            Counts before = count(journal);
            assertEquals(3, before.facts());

            Run refused = ingest(dir, url, journal, second, "ing-salary");
            assertEquals(4, refused.exit(), refused.out());
            assertTrue(refused.out().contains("fits ing-orange"), refused.out());
            Counts after = count(journal);
            assertEquals(before.facts(), after.facts(), "no fact posted");
            assertEquals(before.ingestEvents() + 2, after.ingestEvents(), "one start/complete pair");
            assertEquals("guard_refused", after.lastComplete().status());
            assertEquals("elsewhere:ing-orange", after.lastComplete().guard());

            Run identified = run(dir, "ingest", "--source-type", "ing-csv", "--journal", journal.toString(),
                "--identify", "--format", "json", second.toString());
            assertEquals(0, identified.exit(), identified.out());
            Map<?, ?> json = Json.mapper().readValue(identified.out().strip(), Map.class);
            assertEquals("ing-orange", json.get("suggestion"));
            assertEquals(after, count(journal), "identify writes nothing");

            Run forced = run(dir, "ingest", "--source-type", "ing-csv", "--account", "ing-salary",
                "--sequencer-url", url, "--evidence", dir.resolve("evidence").toString(),
                "--journal", journal.toString(), "--account-guard=off", second.toString());
            assertEquals(0, forced.exit(), forced.out());
            Counts forcedCounts = count(journal);
            assertEquals(after.facts() + 3, forcedCounts.facts());
            assertEquals("off", forcedCounts.lastComplete().guard());

            // Without a journal the guard cannot run: the file ingests as before, and the event has no guard.
            Run unguarded = run(dir, "ingest", "--source-type", "ing-csv", "--account", "ing-orange",
                "--sequencer-url", url, "--evidence", dir.resolve("evidence").toString(), second.toString());
            assertEquals(0, unguarded.exit(), unguarded.out());
            assertNull(count(journal).lastComplete().guard());
        }
    }

    private static Run ingest(Path dir, String url, Path journal, Path file, String account) throws Exception {
        return run(dir, "ingest", "--source-type", "ing-csv", "--account", account, "--sequencer-url", url,
            "--evidence", dir.resolve("evidence").toString(), "--journal", journal.toString(), file.toString());
    }

    private record Run(int exit, String out) {}

    private static Run run(Path dir, String... args) {
        PrintStream real = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            int exit = new CommandLine(new Main()).execute(args);
            return new Run(exit, captured.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(real);
        }
    }

    private record Counts(int facts, int ingestEvents, IngestEvent lastComplete) {}

    private static Counts count(Path journal) {
        int facts = 0;
        int events = 0;
        IngestEvent lastComplete = null;
        try (FramedReader reader = new FramedReader(journal, 0)) {
            FramedReader.Framed framed;
            while ((framed = reader.next()) != null) {
                LogLine line = framed.line();
                if (line instanceof Fact) {
                    facts++;
                } else if (line instanceof IngestEvent event) {
                    events++;
                    lastComplete = event.isStart() ? lastComplete : event;
                }
            }
        }
        return new Counts(facts, events, lastComplete);
    }

    private static void config(Path configDir) throws Exception {
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-orange"
                currency: "AUD"
                balanceSource: statement
              - ref: "ing-salary"
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
}
