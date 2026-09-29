package trex.v2;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import trex.v2.cli.EgressCommand;
import trex.v2.cli.ExportCommand;
import trex.v2.cli.HubCommand;
import trex.v2.cli.ImportCommand;
import trex.v2.cli.IndexCommand;
import trex.v2.cli.IngestCommand;
import trex.v2.cli.ReflowCommand;
import trex.v2.cli.SequencerCommand;
import trex.v2.cli.VerifyCommand;

import java.util.concurrent.Callable;

/**
 * The one artifact, many roles (V2-PROPOSAL.md §5.2, §19). Every role is a subcommand of this
 * single shaded jar; no role is a different artifact and no config is baked in.
 */
@Command(
    name = "trex",
    mixinStandardHelpOptions = true,
    version = "trex-v2 0.1.0-SNAPSHOT",
    description = "The v2 ledger: one artifact, one role per subcommand.",
    // 64 (EX_USAGE) is the documented contract; picocli would use 2.
    exitCodeOnInvalidInput = 64,
    subcommands = {
        SequencerCommand.class,
        HubCommand.class,
        IndexCommand.class,
        IngestCommand.class,
        EgressCommand.class,
        ReflowCommand.class,
        VerifyCommand.class,
        ExportCommand.class,
        ImportCommand.class,
    })
public final class Main implements Callable<Integer> {

    public Main() {}

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    /** The one CLI, with the usage exit code (64, EX_USAGE) applied to every nested command. */
    public static CommandLine commandLine() {
        CommandLine cli = new CommandLine(new Main());
        applyUsageExitCode(cli);
        return cli;
    }

    private static void applyUsageExitCode(CommandLine cli) {
        cli.getCommandSpec().exitCodeOnInvalidInput(64);
        cli.getSubcommands().values().forEach(Main::applyUsageExitCode);
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }
}
