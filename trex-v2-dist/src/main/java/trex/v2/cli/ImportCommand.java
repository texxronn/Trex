package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import trex.v2.importer.HttpSink;
import trex.v2.importer.V1Importer;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code trex import}: the one-shot v1-format reader (V2-IMPLEMENTATION-PLAN.md P0.7). A dev tool,
 * not a migration path — it seeds a fresh v2 log from a private v1 journal through the sequencer.
 */
@Command(name = "import", mixinStandardHelpOptions = true,
    description = "Import a v1-format journal into a running v2 sequencer (dev tool).")
public final class ImportCommand implements Callable<Integer> {

    @Option(names = "--journal-v1", required = true, description = "The v1-format JSONL journal.")
    Path journalV1;

    @Option(names = "--sequencer-url", required = true, description = "Running sequencer base URL.")
    String sequencerUrl;

    @Option(names = "--pins", description = "v1 pins.yaml to import as PIN decisions.")
    Path pins;

    @Override
    public Integer call() throws Exception {
        V1Importer.Report report = V1Importer.importJournal(journalV1, pins, new HttpSink(sequencerUrl));
        System.out.printf("imported facts=%d pairs=%d markExternal=%d dismiss=%d supersede=%d pins=%d%n",
            report.facts(), report.pairs(), report.markExternal(), report.dismiss(), report.supersede(),
            report.pins());
        if (!report.identityMismatches().isEmpty()) {
            System.err.println("IDENTITY MISMATCH: " + report.identityMismatches().size()
                + " ids re-minted differently; the import is not faithful");
            report.identityMismatches().stream().limit(20).forEach(m -> System.err.println("  " + m));
            return 1;
        }
        return 0;
    }
}
