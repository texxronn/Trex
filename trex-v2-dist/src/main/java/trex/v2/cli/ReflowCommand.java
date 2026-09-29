package trex.v2.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/** {@code trex reflow --preview}: show what a candidate rule set would change (V2-PROPOSAL.md §9.3). */
@Command(name = "reflow", mixinStandardHelpOptions = true,
    description = "Preview what a candidate rule set would change (stage P1).")
public final class ReflowCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("trex reflow is implemented in stage P1 (V2-IMPLEMENTATION-PLAN.md §3).");
        return 2;
    }
}
