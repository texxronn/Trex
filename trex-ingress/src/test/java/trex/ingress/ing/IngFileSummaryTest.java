package trex.ingress.ing;

import org.junit.jupiter.api.Test;
import trex.core.Candidate;
import trex.core.Ids;
import trex.core.OccCandidate;
import trex.core.Occurrence;
import trex.ingress.BadRow;
import trex.ingress.DayBatcher;
import trex.ingress.IngressRunner;
import trex.ingress.Parsed;
import trex.ingress.SkippedRow;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local dry run of a whole statement: parse, validate, plan day-atomic calls and compute the
 * external ids the sequencer would assign. No sequencer, nothing sent; prints a summary.
 * <p>
 * Point it at a real export (IDE: add these as VM options; Maven: pass them on the command line):
 * <pre>
 *   mvn -pl trex-ingress test -Dtest=IngFileSummaryTest -Dtrex.ing.file=/path/to/export.csv \
 *       [-Dtrex.ing.sourceType=bw-csv] [-Dtrex.ing.account=ing-savings] [-Dtrex.ing.batchRows=500]
 * </pre>
 * Without {@code trex.ing.file} it summarises the bundled slice, so it also runs in the normal build.
 */
class IngFileSummaryTest {

    private static final int SHOWN = 10;

    @Test
    void summariseWholeFile() {
        Path file = setting("trex.ing.file", "TREX_ING_FILE") instanceof String f
            ? Path.of(f) : IngCsvTest.resource("ing-slice.csv");
        String account = setting("trex.ing.account", "TREX_ING_ACCOUNT") instanceof String a ? a : "ing-savings";
        int batchRows = setting("trex.ing.batchRows", "TREX_ING_BATCH_ROWS") instanceof String b
            ? Integer.parseInt(b) : Integer.MAX_VALUE;
        String sourceType = setting("trex.ing.sourceType", "TREX_ING_SOURCE_TYPE") instanceof String t
            ? t : IngCsv.SOURCE_TYPE;

        Parsed parsed = IngressRunner.parser(sourceType).apply(file, account);
        StringBuilder out = new StringBuilder();
        line(out, "=== %s dry run: %s (account %s) ===", sourceType, file.toAbsolutePath(), account);
        for (SkippedRow s : parsed.skipped()) {
            line(out, "skipped  %s", s);
        }

        if (!parsed.valid()) {
            line(out, "INVALID: %d bad row(s); nothing would be sent", parsed.badRows().size());
            for (BadRow b : parsed.badRows()) {
                line(out, "  %s", b);
            }
            System.out.print(out);
            assertTrue(parsed.valid(), "file has bad rows; see summary above");
            return;
        }

        List<Candidate> rows = parsed.candidates();
        line(out, "rows            %d", rows.size());
        if (rows.isEmpty()) {
            System.out.print(out);
            return;
        }

        // Dates and money.
        TreeMap<LocalDate, Integer> perDay = new TreeMap<>();
        long credits = 0, debits = 0;
        int creditRows = 0, debitRows = 0, zeroRows = 0;
        for (Candidate c : rows) {
            perDay.merge(c.date(), 1, Integer::sum);
            if (c.amount() > 0) { credits += c.amount(); creditRows++; }
            else if (c.amount() < 0) { debits += c.amount(); debitRows++; }
            else { zeroRows++; }
        }
        Map.Entry<LocalDate, Integer> busiest = perDay.entrySet().stream()
            .max(Map.Entry.comparingByValue()).orElseThrow();
        line(out, "dates           %s .. %s (%d days with rows; busiest %s with %d)",
            perDay.firstKey(), perDay.lastKey(), perDay.size(), busiest.getKey(), busiest.getValue());
        line(out, "file order      %s", fileOrder(rows));
        line(out, "credits         %d rows, %s", creditRows, money(credits));
        line(out, "debits          %d rows, %s", debitRows, money(debits));
        if (zeroRows > 0) {
            line(out, "zero amount     %d rows", zeroRows);
        }
        line(out, "net             %s", money(credits + debits));

        // Balance chain, in whichever direction the file runs.
        List<String> fwd = chainBreaks(rows, false);
        List<String> rev = chainBreaks(rows, true);
        boolean reverse = rev.size() < fwd.size();
        List<String> breaks = reverse ? rev : fwd;
        line(out, "balance chain   %s, %d break(s)%s", reverse ? "newest-first" : "oldest-first", breaks.size(),
            breaks.isEmpty() ? "" : " (expected 0 for a complete statement)");
        breaks.stream().limit(SHOWN).forEach(b -> line(out, "  %s", b));
        more(out, breaks.size());

        // Day-atomic call plan; occ is computed per call, exactly as the sequencer does.
        List<List<Candidate>> calls = DayBatcher.split(rows, batchRows);
        line(out, "calls           %d (batch-rows %s): sizes %s", calls.size(),
            batchRows == Integer.MAX_VALUE ? "unlimited" : batchRows,
            calls.stream().map(List::size).toList());

        // Identity.
        int natural = 0, repeats = 0;
        Map<String, List<String>> byId = new LinkedHashMap<>();
        List<String> idLines = new ArrayList<>();
        for (List<Candidate> call : calls) {
            for (OccCandidate oc : Occurrence.assignOcc(call)) {
                Candidate c = oc.c();
                String id = Ids.externalId(Ids.strategyFor(oc));
                if (c.hasReceipt()) {
                    natural++;
                } else if (oc.occ() > 0) {
                    repeats++;
                }
                byId.computeIfAbsent(id, k -> new ArrayList<>()).add(c.candidateRef());
                idLines.add("%-8s %s %-3s %12s  %-10s %s".formatted(c.candidateRef(), id,
                    c.hasReceipt() ? "nk" : "ch" + oc.occ(), money(c.amount()), c.date(), c.rawDescription()));
            }
        }
        line(out, "identity        %d natural key (receipt), %d content hash (%d with occ > 0)",
            natural, rows.size() - natural, repeats);
        if (!parsed.skipped().isEmpty()) {
            line(out, "skipped rows    %d (not final at the bank; re-export once they settle)", parsed.skipped().size());
        }
        List<Map.Entry<String, List<String>>> clashes = byId.entrySet().stream()
            .filter(e -> e.getValue().size() > 1).toList();
        line(out, "same id twice   %d%s", clashes.size(),
            clashes.isEmpty() ? "" : " (a receipt repeated within the file)");
        clashes.stream().limit(SHOWN).forEach(e -> line(out, "  %s <- %s", e.getKey(), e.getValue()));
        more(out, clashes.size());

        line(out, "first rows (ref, externalId, tier/occ, amount, date, rawDescription):");
        idLines.stream().limit(SHOWN).forEach(l -> line(out, "  %s", l));
        more(out, idLines.size());

        System.out.print(out);
    }

    /** Where the rows' dates go in file order: ascending, descending or mixed. */
    private static String fileOrder(List<Candidate> rows) {
        boolean up = true, down = true;
        for (int i = 1; i < rows.size(); i++) {
            int cmp = rows.get(i).date().compareTo(rows.get(i - 1).date());
            up &= cmp >= 0;
            down &= cmp <= 0;
        }
        return up && down ? "single day" : up ? "dates ascending" : down ? "dates descending" : "dates mixed";
    }

    /** Rows whose balance is not the neighbouring balance plus this row's amount. */
    private static List<String> chainBreaks(List<Candidate> rows, boolean newestFirst) {
        List<String> breaks = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            Candidate prev = rows.get(newestFirst ? i : i - 1);
            Candidate next = rows.get(newestFirst ? i - 1 : i);
            long expected = prev.balance() + next.amount();
            if (expected != next.balance()) {
                breaks.add("%s: balance %s, expected %s (%s + %s)".formatted(next.candidateRef(),
                    money(next.balance()), money(expected), money(prev.balance()), money(next.amount())));
            }
        }
        return breaks;
    }

    private static String setting(String property, String env) {
        String v = System.getProperty(property);
        if (v == null || v.isBlank()) {
            v = System.getenv(env);
        }
        return v == null || v.isBlank() ? null : v;
    }

    private static String money(long cents) {
        return (cents < 0 ? "-" : "") + Math.abs(cents) / 100 + "." + "%02d".formatted(Math.abs(cents) % 100);
    }

    private static void more(StringBuilder out, int total) {
        if (total > SHOWN) {
            line(out, "  ... %d more", total - SHOWN);
        }
    }

    private static void line(StringBuilder out, String format, Object... args) {
        out.append(format.formatted(args)).append('\n');
    }
}
