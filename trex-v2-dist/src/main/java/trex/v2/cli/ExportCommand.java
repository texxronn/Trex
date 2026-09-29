package trex.v2.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/** {@code trex export --format csv|json|sqlite}: the living truth, portable (V2-PROPOSAL.md §1). */
@Command(name = "export", mixinStandardHelpOptions = true,
    description = "Export the derived ledger as csv, json or sqlite (stage P1).")
public final class ExportCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("trex export is implemented in stage P1 (V2-IMPLEMENTATION-PLAN.md §3).");
        return 2;
    }
}
