package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/** {@code trex egress archive|firefly}: the byte mirror and the Firefly projection (plan P3). */
@Command(name = "egress", mixinStandardHelpOptions = true,
    description = "Project trex outward: the archive mirror and the Firefly projection (stage P3).",
    subcommands = {EgressCommand.Archive.class, EgressCommand.Firefly.class})
public final class EgressCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }

    @Command(name = "archive", mixinStandardHelpOptions = true,
        description = "Byte mirror plus evidence copy (stage P3).")
    static final class Archive implements Callable<Integer> {
        @Override
        public Integer call() {
            System.err.println("trex egress archive is implemented in stage P3 (V2-IMPLEMENTATION-PLAN.md §3).");
            return 2;
        }
    }

    @Command(name = "firefly", mixinStandardHelpOptions = true,
        description = "Firefly plan/apply/verify convergence (stage P3).")
    static final class Firefly implements Callable<Integer> {
        @Override
        public Integer call() {
            System.err.println("trex egress firefly is implemented in stage P3 (V2-IMPLEMENTATION-PLAN.md §3).");
            return 2;
        }
    }
}
