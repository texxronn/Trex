package trex.v2.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/** {@code trex ingest}: adapters to evidence + facts (V2-IMPLEMENTATION-PLAN.md P4). */
@Command(name = "ingest", mixinStandardHelpOptions = true,
    description = "Parse a source into facts; whole-file validation, day batching (stage P4).")
public final class IngestCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("trex ingest is implemented in stage P4 (V2-IMPLEMENTATION-PLAN.md §3).");
        return 2;
    }
}
