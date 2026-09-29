package trex.v2.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/** {@code trex hub}: index owner, blotter API + UI, decision path (V2-IMPLEMENTATION-PLAN.md P1). */
@Command(name = "hub", mixinStandardHelpOptions = true,
    description = "Run the index owner, blotter API and UI (stage P1).")
public final class HubCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("trex hub is implemented in stage P1 (V2-IMPLEMENTATION-PLAN.md §3).");
        return 2;
    }
}
