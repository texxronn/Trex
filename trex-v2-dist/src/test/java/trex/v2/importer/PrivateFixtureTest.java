package trex.v2.importer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.index.IndexLock;
import trex.v2.index.Indexer;
import trex.v2.log.ConfigLoader;
import trex.v2.log.JsonlJournal;
import trex.v2.sequencer.Sequencer;
import trex.v2.sequencer.api.BatchResponse;
import trex.v2.sequencer.api.DecisionBatch;
import trex.v2.sequencer.api.FactBatch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The one acceptance test on the private fixture (V2-IMPLEMENTATION-PLAN.md §3 P0): seeding the v1
 * journal preserves identity, and the index rebuild equals the incremental pass. It is tagged
 * {@code fixture} and skipped with a reason when no journal is present, so {@code mvn verify} is
 * green on a fresh clone.
 */
@Tag("fixture")
class PrivateFixtureTest {

    private static final Instant ASOF = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void seedingPreservesIdentityAndRebuilds(@TempDir Path dir) throws Exception {
        Path fixture = fixture();
        Assumptions.assumeTrue(fixture != null,
            "no private journal at deploy/dev/journal/ or TREX_DEV_FIXTURE; skipping");
        Path configDir = configDir();

        ConfigLoader.Loaded loaded = ConfigLoader.load(configDir);
        Path v2Journal = dir.resolve("trex.jsonl");
        try (Sequencer sequencer = new Sequencer(new JsonlJournal(v2Journal), loaded.registry(),
                loaded.config().categories(), Clock.fixed(ASOF, ZoneOffset.UTC))) {
            V1Importer.Report report = V1Importer.importJournal(fixture, configDir.resolve("pins.yaml"),
                new Sink(sequencer));
            assertEquals(0, report.identityMismatches().size(),
                "every v1 id must re-mint identically: " + report.identityMismatches());
            System.out.println("fixture imported: " + report);
        }

        Path db = dir.resolve("trex.sqlite");
        try (IndexLock ignored = IndexLock.acquire(db);
             Indexer indexer = Indexer.open(db, loaded.config())) {
            indexer.apply(v2Journal, ASOF);
            String incremental = indexer.derivedFingerprint();
            indexer.rebuild(v2Journal, ASOF);
            assertEquals(incremental, indexer.derivedFingerprint());
        }
    }

    private static Path fixture() {
        String override = System.getenv("TREX_DEV_FIXTURE");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            return Files.isDirectory(p) ? p.resolve("journal.jsonl") : (Files.exists(p) ? p : null);
        }
        Path p = Path.of("..", "deploy", "dev", "journal", "journal.jsonl");
        return Files.exists(p) ? p : null;
    }

    private static Path configDir() {
        Path fromModule = Path.of("..", "deploy", "config");
        return Files.isDirectory(fromModule) ? fromModule : Path.of("deploy", "config");
    }

    private record Sink(Sequencer sequencer) implements V1Importer.Sink {
        @Override
        public BatchResponse facts(FactBatch batch) {
            return sequencer.submitFacts(batch);
        }

        @Override
        public BatchResponse decisions(DecisionBatch batch) {
            return sequencer.submitDecisions(batch);
        }
    }
}
