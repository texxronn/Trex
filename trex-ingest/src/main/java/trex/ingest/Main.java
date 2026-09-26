package trex.ingest;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code trex-ingest --source-type <type> --account <accountRef> --sequencer-url <url> [--batch-rows N] [--no-gzip] <source>}
 * <p>
 * Exit codes are a contract (README): 0 committed, 1 invalid source, 2 not fully committed,
 * 3 transport failure, 64 usage.
 */
@Command(name = "trex-ingest", mixinStandardHelpOptions = true,
    // 64 (EX_USAGE) is the documented contract; picocli would use 2.
    exitCodeOnInvalidInput = 64,
    description = "Read a bank statement or feed and post its rows to the sequencer as candidates.")
public final class Main implements Callable<Integer> {

    @Option(names = "--source-type", required = true, paramLabel = "<type>",
        description = "Which parser reads the source, stamped on every candidate "
            + "(known: ing-csv, bw-csv, cba-csv, cba-pdf).")
    private String sourceType;

    @Option(names = "--account", required = true, paramLabel = "<accountRef>",
        description = "Registry key the rows belong to.")
    private String account;

    @Option(names = "--sequencer-url", required = true, paramLabel = "<url>",
        description = "Base URL of the sequencer API.")
    private URI sequencerUrl;

    @Option(names = "--batch-rows", paramLabel = "N",
        description = "Soft row target per call; days are never split (default: the whole source in one call).")
    private int batchRows = Integer.MAX_VALUE;

    @Option(names = "--no-gzip", description = "Send and accept plain bodies, to watch the wire.")
    private boolean noGzip;

    @Parameters(index = "0", paramLabel = "<source>", description = "The instance to read; for ing-csv, the statement file.")
    private Path source;

    public static void main(String[] args) {
        int exit = new CommandLine(new Main()).execute(args);
        if (exit != CommandLine.ExitCode.OK) {
            System.exit(exit);
        }
    }

    @Override
    public Integer call() {
        if (batchRows <= 0) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--batch-rows must be positive");
        }
        try {
            IngestRunner.parser(sourceType);   // fail as a usage error, before anything is read
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(new CommandLine(this), e.getMessage());
        }
        return IngestRunner.run(sourceType, account, sequencerUrl, source, batchRows, !noGzip, System.out);
    }
}
