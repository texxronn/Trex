package trex.v2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Decision;
import trex.v2.core.Envelope;
import trex.v2.core.Fact;
import trex.v2.core.IngestEvent;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Profiles;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.TransferRules;
import trex.v2.core.config.User;
import trex.v2.index.Indexer;
import trex.v2.log.JsonlJournal;
import trex.v2.log.Streams;
import trex.v2.sequencer.Sequencer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * §14.1 acceptance: export a log and ingest it into an empty sequencer with identical config; the
 * lines match the source and the derived state is identical row-for-row.
 */
class StreamE2ETest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    private static DeriveConfig config() {
        Registry registry = new Registry(
            Map.of("ing-savings", new Account("ing-savings", "AUD", BalanceSource.STATEMENT, 7),
                "ing-orange", new Account("ing-orange", "AUD", BalanceSource.STATEMENT, 7)),
            Map.of("ron", new User("ron", "Ron", true, "weekly")));
        return new DeriveConfig(registry,
            RuleSet.compile("t.yaml", new RuleSet.File(List.of("GROCERIES"), List.of())),
            TransferRules.defaults(List.of("Transfer")), Profiles.empty(), "sha256:cfg");
    }

    private static Fact fact(long n, String id, String account, long amount, long balance, String raw) {
        return new Fact(new Envelope(n, Fact.KIND, Envelope.VERSION, AT.toEpochMilli(),
            "Dev1    ", "SRC00001", "        "), id, account, LocalDate.of(2026, 9, 1), amount, balance,
            raw, null, 0, Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1");
    }

    @Test
    void exportIngestRoundTripsLinesAndDerivedState(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("source.jsonl");
        try (JsonlJournal j = new JsonlJournal(source)) {
            j.appendBatch(List.of(
                fact(1, "A", "ing-savings", -1000, 900, "Transfer to Orange 4321"),
                fact(2, "B", "ing-orange", 1000, 1400, "Transfer from Savings 4321"),
                new Decision.MarkExternal(new Envelope(3, Decision.KIND, Envelope.VERSION,
                    AT.toEpochMilli(), "Dev1    ", "SRC00001", "        "), "A", "not a transfer",
                    trex.v2.core.Actor.USER, "ron"),
                new IngestEvent(new Envelope(4, IngestEvent.KIND, Envelope.VERSION, AT.toEpochMilli(),
                    "Dev1    ", "SRC00001", "        "), "complete", "batch-1", null, "x.csv",
                    "ing-savings", "ing-csv", "ing-csv/1", 1, 0, 0, "ok")));
        }

        Path stream = dir.resolve("stream.jsonl.gz");
        Streams.Manifest manifest = Streams.export(source, stream, null, "sha256:cfg",
            DeriveConfig.DERIVE_VERSION);
        assertEquals(4, manifest.count());

        List<String> lines = Streams.readLines(stream);
        Path target = dir.resolve("target.jsonl");
        try (Sequencer seq = new Sequencer(new JsonlJournal(target), config().registry(),
                config().categories(), Clock.fixed(AT, java.time.ZoneOffset.UTC), "Dev1    ", null)) {
            var response = seq.submitStream(lines);
            assertNull(response.error());
            assertEquals(4, response.appended());
            assertEquals(4, response.headN());
        }

        // Line for line, the target equals the source.
        assertEquals(Files.readAllLines(source), Files.readAllLines(target));

        // Derived row-for-row: the same fingerprint from the source and the target logs.
        String sourceFp;
        try (Indexer indexer = Indexer.open(dir.resolve("source.sqlite"), config())) {
            indexer.apply(source, AT);
            sourceFp = indexer.derivedFingerprint();
        }
        try (Indexer indexer = Indexer.open(dir.resolve("target.sqlite"), config())) {
            indexer.apply(target, AT);
            assertEquals(sourceFp, indexer.derivedFingerprint());
        }
    }
}
