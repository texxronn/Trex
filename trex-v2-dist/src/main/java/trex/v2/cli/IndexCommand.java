package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import trex.v2.index.IndexLock;
import trex.v2.index.Indexer;
import trex.v2.log.ConfigLoader;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;

/**
 * {@code trex index [--rebuild]}: materialise the read model (V2-PROPOSAL.md §7.4). {@code
 * --rebuild} is the offline spelling of "stop the hub, delete the file, start it": it takes the
 * same lock and refuses while the hub is live.
 */
@Command(name = "index", mixinStandardHelpOptions = true,
    description = "Materialise the derived SQLite index; --rebuild is offline and exclusive.")
public final class IndexCommand implements Callable<Integer> {

    @Option(names = "--journal", required = true, description = "Path to trex.jsonl.")
    Path journal;

    @Option(names = "--config", required = true, description = "Config directory.")
    Path config;

    @Option(names = "--index", required = true, description = "Path to trex.sqlite.")
    Path index;

    @Option(names = "--as-of", description = "Derivation instant (ISO-8601); defaults to now.")
    String asOf;

    @Option(names = "--rebuild", description = "Wipe and rebuild from the log (offline).")
    boolean rebuild;

    @Override
    public Integer call() {
        Instant at = asOf == null ? Instant.now() : Instant.parse(asOf);
        ConfigLoader.Loaded loaded = ConfigLoader.load(config);
        try (IndexLock ignored = IndexLock.acquire(index);
             Indexer indexer = Indexer.open(index, loaded.config())) {
            if (rebuild) {
                indexer.rebuild(journal, at);
                System.out.println("rebuilt " + index + " at asOf " + at);
            } else {
                boolean changed = indexer.apply(journal, at);
                System.out.println((changed ? "applied" : "up to date at") + " " + index
                    + " (offset " + indexer.offset() + ")");
            }
        }
        return 0;
    }
}
