package trex.egress.hledger;

import com.fasterxml.jackson.databind.JsonNode;
import trex.core.CanonicalEvent;
import trex.journal.Json;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code trex-egress-hledger --gateway-url <url> --out <file> [--accounts <hledger.yaml>]
 * [--no-assert] [--stdout]}
 * <p>
 * Rewrites a plain-text hledger journal from the gateway (SPEC §5.9).
 * <p>
 * There is no cache, no idempotency and no drift, because the file is regenerated whole every run.
 * That is the entire difference from the Firefly egress: nearly all of that module's machinery
 * exists because its target is a stateful service that can also be edited by hand. Here, a
 * correction made in the output would be overwritten on the next run — which is the point, since
 * it forces every correction back into trex where the other consumers can see it.
 */
@Command(name = "hledger", mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = 64,
    description = "Regenerate an hledger journal from the trex gateway.")
public final class HledgerCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(HledgerCommand.class);
    private static final int PAGE = 500;

    @Option(names = "--gateway-url", paramLabel = "<url>", description = "(default: ${DEFAULT-VALUE})")
    private URI gatewayUrl = URI.create("http://127.0.0.1:8085");

    @Option(names = "--out", paramLabel = "<file>", description = "Journal to write.")
    private Path out;

    @Option(names = "--accounts", paramLabel = "<file>",
        description = "hledger.yaml: where each trex account sits in the account tree. "
            + "Without it everything lands under assets:.")
    private Path accountsFile;

    @Option(names = "--no-assert",
        description = "Omit balance assertions. They are the reason this egress is useful, so "
            + "this is for diagnosing a file that will not load.")
    private boolean noAssert;

    @Option(names = "--stdout", description = "Write to stdout instead of a file.")
    private boolean toStdout;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();


    @Override
    public Integer call() throws Exception {
        if (out == null && !toStdout) {
            System.err.println("--out or --stdout is required.");
            return 64;
        }
        // Which accounts are declared comes from the gateway, not from hledger.yaml: the
        // registry has one owner and copying balanceSource here would let two files disagree
        // about the same account (§5.7).
        Accounts accounts = Accounts.load(accountsFile).withDeclared(declaredRefs());

        List<Ledger.Row> rows = new ArrayList<>();
        long asOfN = 0;
        String revision = null;
        for (int page = 1; ; page++) {
            JsonNode body = get("/api/snapshot?view=transactions&sort=n:asc&size=" + PAGE + "&page=" + page);
            asOfN = body.path("asOfN").asLong();
            revision = body.path("rulesRevision").asText(null);
            JsonNode rowsNode = body.path("rows");
            JsonNode categories = body.path("categories");
            for (JsonNode row : rowsNode) {
                CanonicalEvent line = Json.mapper().treeToValue(row, CanonicalEvent.class);
                rows.add(new Ledger.Row(line,
                    categories.path(String.valueOf(line.n())).path("category").asText("UNCATEGORIZED")));
            }
            if (rowsNode.isEmpty() || (long) page * PAGE >= body.path("total").asInt()) {
                break;
            }
        }

        Ledger ledger = new Ledger(accounts, !noAssert);
        String text = ledger.render(rows, asOfN, revision);
        if (toStdout) {
            System.out.print(text);
            return CommandLine.ExitCode.OK;
        }
        // Written through a temp file and renamed, so a reader never sees a half-written journal
        // and a failed run leaves the previous one intact.
        Path temp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING);

        log.info("wrote {} ({} transactions, n={}, rules={})", out, rows.size(), asOfN, revision);
        report(accounts, rows, ledger);
        return CommandLine.ExitCode.OK;
    }

    /**
     * What the file cannot say about itself: where a mapping is missing, and where the bank's
     * running balance knows about history the journal does not. The second is the useful one —
     * hledger is about to fail an assertion, and this says which account and by how much before
     * the run even starts.
     */
    private void report(Accounts accounts, List<Ledger.Row> rows, Ledger ledger) {
        long unmapped = rows.stream().map(r -> r.line().accountRef())
            .distinct().filter(ref -> !accounts.maps(ref)).count();
        if (unmapped > 0) {
            log.warn("{} accounts have no entry in hledger.yaml and defaulted to assets: — a "
                + "credit card or a loan placed there will misreport net worth", unmapped);
        }
        if (accounts.income().isEmpty()) {
            log.warn("no income categories declared in hledger.yaml; the contra account falls back "
                + "to the sign, which files every refund under income: and overstates both sides");
        }
        ledger.unresolvedDays().forEach((ref, unresolved) -> {
            if (unresolved > 0) {
                log.warn("{}: {} days whose order the balance column could not resolve — the bank "
                    + "counted a line trex does not have on each of them, so they carry no "
                    + "assertion; `hledger reg {}` against the statement finds them", ref,
                    unresolved, accounts.of(ref));
            }
        });
        for (Ledger.Opening o : ledger.openings()) {
            if (o.gap() == 0) {
                log.info("opening {} {} as of {}", o.ref(),
                    Ledger.money(o.cents(), o.currency()), o.date());
            } else {
                log.warn("opening {} {} as of {} — the bank's balance accounts for {} the journal "
                    + "does not; expect an assertion to fail where that history is missing",
                    o.ref(), Ledger.money(o.cents(), o.currency()), o.date(),
                    Ledger.money(o.gap(), o.currency()));
            }
        }
    }

    private java.util.Set<String> declaredRefs() throws IOException, InterruptedException {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (JsonNode a : get("/api/accounts")) {
            if ("declared".equals(a.path("balanceSource").asText())) {
                out.add(a.path("ref").asText());
            }
        }
        return out;
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(
            HttpRequest.newBuilder(gatewayUrl.resolve(path)).GET().timeout(Duration.ofSeconds(60)).build(),
            HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            throw new IOException("GET " + path + " -> " + r.statusCode() + ": " + r.body());
        }
        return Json.mapper().readTree(r.body());
    }
}
