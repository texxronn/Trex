package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import trex.v2.index.IndexLock;
import trex.v2.index.Indexer;
import trex.v2.log.ConfigLoader;
import trex.v2.log.Recovery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code trex verify}: framing, index equivalence and the status strip (V2-PROPOSAL.md §14, §15).
 * It rebuilds into a scratch file and compares fingerprints; a mismatch is a non-zero exit.
 */
@Command(name = "verify", mixinStandardHelpOptions = true,
    description = "Check framing and rebuild equivalence, then print the status strip.")
public final class VerifyCommand implements Callable<Integer> {

    @Option(names = "--journal", required = true, description = "Path to trex.jsonl.")
    Path journal;

    @Option(names = "--config", required = true, description = "Config directory.")
    Path config;

    @Option(names = "--index", description = "Use this index path instead of a scratch file.")
    Path index;

    @Option(names = "--as-of", description = "Derivation instant (ISO-8601); defaults to now.")
    String asOf;

    @Override
    public Integer call() throws Exception {
        Instant at = asOf == null ? Instant.now() : Instant.parse(asOf);
        long head = Recovery.scanToLastCompleteRecord(journal);
        ConfigLoader.Loaded loaded = ConfigLoader.load(config);

        boolean scratch = index == null;
        Path db = scratch ? Files.createTempFile("trex-verify", ".sqlite") : index;
        boolean ok;
        try (IndexLock ignored = IndexLock.acquire(db);
             Indexer indexer = Indexer.open(db, loaded.config())) {
            indexer.apply(journal, at);
            String incremental = indexer.derivedFingerprint();
            Map<String, Long> counts = indexer.counts();
            java.util.Set<String> declared = loaded.config().registry().accounts().values().stream()
                .filter(a -> a.balanceSource() == trex.v2.core.config.BalanceSource.DECLARED)
                .map(trex.v2.core.config.Account::ref)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
            Map<String, trex.v2.core.derive.Reconciliation.AccountResult> reconcile =
                trex.v2.core.derive.Reconciliation.reconcile(indexer.currentFacts(), declared);
            boolean reconcileOk = reconcile.values().stream()
                .allMatch(trex.v2.core.derive.Reconciliation.AccountResult::balances);
            indexer.rebuild(journal, at);
            String rebuilt = indexer.derivedFingerprint();
            ok = incremental.equals(rebuilt) && reconcileOk;

            System.out.println("journal:  " + journal + " (head " + head + " bytes)");
            System.out.println("config:   " + loaded.config().configRevision()
                + "  derive " + trex.v2.core.config.DeriveConfig.DERIVE_VERSION
                + "  hash " + trex.v2.core.config.DeriveConfig.HASH_VERSION);
            counts.forEach((table, count) -> System.out.printf("  %-20s %d%n", table, count));
            reconcile.values().forEach(r -> System.out.printf("  reconcile %-16s %s%n",
                r.accountRef(), r.status().name().toLowerCase(java.util.Locale.ROOT)));
            System.out.println(ok
                ? "index:    rebuild ≡ incremental; reconciliation green"
                : "verify:   FAILED (rebuild mismatch or reconciliation broken)");
        } finally {
            if (scratch) {
                Files.deleteIfExists(db);
                Files.deleteIfExists(db.resolveSibling(db.getFileName() + ".lock"));
            }
        }
        return ok ? 0 : 1;
    }
}
