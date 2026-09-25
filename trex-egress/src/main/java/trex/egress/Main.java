package trex.egress;

import trex.egress.archive.ArchiveCommand;
import trex.egress.firefly.FireflyCommand;
import trex.egress.hledger.HledgerCommand;
import trex.egress.sqlite.SqliteCommand;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code trex-egress <target> [options]} — every journal consumer that writes outside trex.
 * SPEC §5.2, §5.3, §5.8, §5.9.
 * <p>
 * One jar and one subcommand per target, because the four were never independent: they shared the
 * follower loop (§5.1) by copying it, and four jars meant four {@code --help} outputs, none of
 * which could tell you what the other three were.
 * <p>
 * The targets divide on a line worth knowing, and it is not the line the module names used to
 * suggest. {@code archive} and {@code sqlite} are <b>log mirrors</b>: they copy journal lines, keep
 * a byte offset, and can be rebuilt from the journal at any time. {@code firefly} and
 * {@code hledger} are <b>projections</b>: they read resolved units through the consumer API (§5.7)
 * so that categories come from the single owner, and what they emit is a different shape from what
 * they read. A mirror that lost its cursor re-reads; a projection that lost its state re-derives.
 */
@Command(name = "trex-egress", mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = 64,
    description = "Project or mirror the trex journal to a target.",
    subcommands = {
        ArchiveCommand.class,   // §5.2  mirror: framed JSONL copy
        SqliteCommand.class,    // §5.3  mirror: SQLite WAL
        FireflyCommand.class,   // §5.8  projection: Firefly III
        HledgerCommand.class,   // §5.9  projection: plain-text ledger
    })
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        int exit = new CommandLine(new Main()).execute(args);
        if (exit != CommandLine.ExitCode.OK) {
            System.exit(exit);
        }
    }
}
