package trex.ingress.ing;

import trex.core.BatchStatus;
import trex.core.Candidate;
import trex.core.CandidateResult;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/** Parse, validate the whole file, send day-atomic batches, print per-row status. */
public final class IngressRunner {

    public static final int OK = 0;
    public static final int INVALID_FILE = 1;
    public static final int NOT_COMMITTED = 2;
    public static final int TRANSPORT_FAILURE = 3;

    private IngressRunner() {}

    public static int run(String accountRef, URI url, Path file, int batchRows, boolean gzip, PrintStream out) {
        IngCsv.Parsed parsed = IngCsv.parse(file, accountRef);
        if (!parsed.valid()) {
            out.println("file rejected, nothing sent; bad rows:");
            parsed.badRows().forEach(b -> out.println("  " + b));
            return INVALID_FILE;
        }
        TrexClient client = new TrexClient(url, gzip);
        int exit = OK;
        for (List<Candidate> call : DayBatcher.split(parsed.candidates(), batchRows)) {
            TrexClient.Response response;
            try {
                response = client.post(call);
            } catch (IOException e) {
                out.println("request failed: " + e.getMessage());
                return TRANSPORT_FAILURE;
            } catch (InterruptedException e) {
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
