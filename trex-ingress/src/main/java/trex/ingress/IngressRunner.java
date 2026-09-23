package trex.ingress;

import trex.core.BatchStatus;
import trex.core.Candidate;
import trex.core.CandidateResult;
import trex.ingress.bw.BwCsv;
import trex.ingress.cba.CbaCsv;
import trex.ingress.cba.CbaPdf;
import trex.ingress.ing.IngCsv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;

/** Parse, validate the whole source, send day-atomic batches, print per-row status. */
public final class IngressRunner {

    private static final Logger log = LoggerFactory.getLogger(IngressRunner.class);

    public static final int OK = 0;
    public static final int INVALID_FILE = 1;
    public static final int NOT_COMMITTED = 2;
    public static final int TRANSPORT_FAILURE = 3;

    private IngressRunner() {}

    /** The parser for each source type (SPEC §4); {@code --source-type} is the only binding. */
    public static BiFunction<Path, String, Parsed> parser(String sourceType) {
        return switch (sourceType) {
            case IngCsv.SOURCE_TYPE -> IngCsv::parse;
            case BwCsv.SOURCE_TYPE -> BwCsv::parse;
            case CbaCsv.SOURCE_TYPE -> CbaCsv::parse;
            case CbaPdf.SOURCE_TYPE -> CbaPdf::parse;
            default -> throw new IllegalArgumentException("unknown source type: " + sourceType + " (known: "
                + String.join(", ", IngCsv.SOURCE_TYPE, BwCsv.SOURCE_TYPE, CbaCsv.SOURCE_TYPE, CbaPdf.SOURCE_TYPE)
                + ")");
        };
    }

    public static int run(String sourceType, String accountRef, URI url, Path source, int batchRows, boolean gzip, PrintStream out) {
        Parsed parsed = parser(sourceType).apply(source, accountRef);
        if (!parsed.valid()) {
            out.println("file rejected, nothing sent; bad rows:");
            parsed.badRows().forEach(b -> out.println("  " + b));
            return INVALID_FILE;
        }
        // Reported, never silent: rows the bank has not finalised are not sent (SPEC §4).
        if (!parsed.skipped().isEmpty()) {
            out.println("skipped " + parsed.skipped().size() + " row(s) the bank has not finalised:");
            parsed.skipped().forEach(s -> out.println("  " + s));
        }
        TrexClient client = new TrexClient(url, gzip);
        int exit = OK;
        for (List<Candidate> call : DayBatcher.split(parsed.candidates(), batchRows)) {
            TrexClient.Response response;
            try {
                response = client.post(call);
            } catch (IOException e) {
                log.warn("posting {} candidates to the sequencer failed", call.size(), e);
                out.println("request failed: " + e.getMessage());
                return TRANSPORT_FAILURE;
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                out.println("interrupted");
                return TRANSPORT_FAILURE;
            }
            out.printf("batch %s: %s%n", response.batchHandle(), response.batchStatus());
            response.results().forEach(r -> out.println("  " + describe(r)));
            if (response.batchStatus() != BatchStatus.COMMITTED) {
                exit = NOT_COMMITTED;
            }
        }
        return exit;
    }

    static String describe(CandidateResult r) {
        return switch (r) {
            case CandidateResult.Resolved x -> "%s Resolved %s n=%d".formatted(x.candidateRef(), x.externalId(), x.n());
            case CandidateResult.DroppedDuplicate x -> "%s DroppedDuplicate %s".formatted(x.candidateRef(), x.externalId());
            case CandidateResult.Held x -> "%s Held %s".formatted(x.candidateRef(), x.externalId());
            case CandidateResult.Flagged x -> "%s Flagged %s %s".formatted(x.candidateRef(), x.externalId(), x.flags());
            case CandidateResult.Rejected x -> "%s Rejected %s".formatted(x.candidateRef(), x.reason());
        };
    }
}
