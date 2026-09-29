package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import trex.v2.egress.export.Exporter;
import trex.v2.egress.hub.HubClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code trex export}: the living truth, portable (V2-PROPOSAL.md §1, the crown checklist #10). It
 * reads the hub's API and writes a new file — csv, json, or a fresh SQLite — never the live index.
 */
@Command(name = "export", mixinStandardHelpOptions = true,
    description = "Export the derived ledger as csv, json or sqlite.")
public final class ExportCommand implements Callable<Integer> {

    @Option(names = "--hub-url", required = true, description = "The hub base URL.")
    String hubUrl;

    @Option(names = "--format", required = true, description = "csv | json | sqlite.")
    String format;

    @Option(names = "--out", description = "Output file; required for sqlite, stdout otherwise.")
    Path out;

    @Override
    public Integer call() throws Exception {
        HubClient hub = new HubClient(hubUrl);
        List<HubClient.LedgerRow> rows = hub.allLedger();
        Map<String, String> currency = hub.currencyByAccount();
        String body;
        switch (format) {
            case "csv" -> body = Exporter.csv(rows, currency);
            case "json" -> body = Exporter.json(rows);
            case "sqlite" -> {
                if (out == null) {
                    System.err.println("--out is required for sqlite");
                    return 64;
                }
                Exporter.sqlite(out, rows, currency);
                System.out.printf("exported %d transaction(s) to %s%n", rows.size(), out);
                return 0;
            }
            default -> {
                System.err.println("--format must be csv, json or sqlite, not '" + format + "'");
                return 64;
            }
        }
        if (out == null) {
            System.out.print(body);
        } else {
            Files.writeString(out, body, StandardCharsets.UTF_8);
            System.err.printf("exported %d transaction(s) to %s%n", rows.size(), out);
        }
        return 0;
    }
}
