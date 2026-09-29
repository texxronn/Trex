package trex.v2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.log.JsonlJournal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §15.12: one artifact, every role. The subcommand list is complete, and the implemented roles
 * run from the same {@link Main} against a scratch fixture.
 */
class DistCliTest {

    @Test
    void listsEverySubcommand() {
        CommandLine cli = Main.commandLine();
        assertTrue(cli.getSubcommands().keySet().containsAll(
            List.of("sequencer", "hub", "index", "ingest", "egress", "reflow", "verify", "export", "import")),
            "every role is a subcommand: " + cli.getSubcommands().keySet());
        assertTrue(cli.getSubcommands().get("egress").getSubcommands().keySet().containsAll(
            List.of("archive", "firefly")));
    }

    @Test
    void usageErrorExits64() {
        // v1's documented contract: 64 (EX_USAGE) on bad usage, not picocli's default 2.
        assertEquals(64, Main.commandLine().execute("sequencer"));
    }

    @Test
    void recoveryDrillStopHubWipeIndexRebuildVerify(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                new Fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, 900, "COLES 1234", null, 0,
                    Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1",
                    Instant.parse("2026-09-30T00:00:00Z"))));
        }
        Path index = dir.resolve("trex.sqlite");
        CommandLine cli = Main.commandLine();

        // Warm the index, then run the drill: stop the hub (none here), wipe, rebuild, verify.
        assertEquals(0, cli.execute("index", "--journal", journal.toString(), "--config", configDir.toString(),
            "--index", index.toString(), "--rebuild", "--as-of", "2026-10-01T00:00:00Z"));
        assertTrue(Files.exists(index));
        Files.delete(index);
        Files.deleteIfExists(index.resolveSibling(index.getFileName() + ".lock"));

        assertEquals(0, cli.execute("index", "--journal", journal.toString(), "--config", configDir.toString(),
            "--index", index.toString(), "--rebuild", "--as-of", "2026-10-01T00:00:00Z"));
        assertEquals(0, cli.execute("verify", "--journal", journal.toString(), "--config", configDir.toString(),
            "--as-of", "2026-10-01T00:00:00Z"));
    }

    private static void config(Path configDir) throws Exception {
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
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

    @Test
    void indexAndVerifyRunFromTheOneMain(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
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

        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                new Fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, 0, "COLES 1234", null, 0,
                    Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1",
                    Instant.parse("2026-09-30T00:00:00Z")),
                new Decision.MarkExternal(2, "a", "ordinary", Actor.USER, "ron",
                    Instant.parse("2026-09-30T00:00:00Z"))));
        }
        Path index = dir.resolve("trex.sqlite");
        int indexExit = new CommandLine(new Main()).execute("index", "--journal", journal.toString(),
            "--config", configDir.toString(), "--index", index.toString(), "--rebuild",
            "--as-of", "2026-10-01T00:00:00Z");
        assertEquals(0, indexExit);
        assertTrue(Files.exists(index));

        int verifyExit = new CommandLine(new Main()).execute("verify", "--journal", journal.toString(),
            "--config", configDir.toString(), "--as-of", "2026-10-01T00:00:00Z");
        assertEquals(0, verifyExit);
    }
}
