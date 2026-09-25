package trex.ingest.ing;

import trex.core.Candidate;
import trex.core.Provenance;
import trex.ingest.BadRow;
import trex.ingest.Cents;
import trex.ingest.Csv;
import trex.ingest.Parsed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source type {@code ing-csv}: ING CSV → candidates (SPEC §4). All ING-specific knowledge lives here.
 * The whole file is validated; any bad value makes the file invalid (nothing is sent).
 */
public final class IngCsv {

    public static final String SOURCE_TYPE = "ing-csv";
    static final List<String> HEADER = List.of("Date", "Description", "Credit", "Debit", "Balance");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern RECEIPT = Pattern.compile("Receipt (No )?(\\d+)");

    private IngCsv() {}

    public static Parsed parse(Path file, String accountRef) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8), file.getFileName().toString(), accountRef);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Parsed parse(String content, String fileName, String accountRef) {
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        List<Csv.Record> records;
        try {
            records = Csv.parse(content);
        } catch (Csv.CsvException e) {
            return new Parsed(List.of(), List.of(new BadRow(fileName, e.line(), "-", "", e.getMessage())));
        }
        List<BadRow> bad = new ArrayList<>();
        if (records.isEmpty() || !records.getFirst().fields().equals(HEADER)) {
            String got = records.isEmpty() ? "" : String.join(",", records.getFirst().fields());
            return new Parsed(List.of(), List.of(new BadRow(fileName, 1, "header", got,
                "expected header " + String.join(",", HEADER))));
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Csv.Record r : records.subList(1, records.size())) {
            if (r.fields().size() == 1 && r.fields().getFirst().isEmpty()) {
                continue;   // blank line
            }
            Candidate c = row(fileName, accountRef, r, bad);
            if (c != null) {
                candidates.add(c);
            }
        }
        return new Parsed(bad.isEmpty() ? List.copyOf(candidates) : List.of(), List.copyOf(bad));
    }

    private static Candidate row(String file, String accountRef, Csv.Record r, List<BadRow> bad) {
        int line = r.line();
        if (r.fields().size() != HEADER.size()) {
            bad.add(new BadRow(file, line, "-", String.join(",", r.fields()),
                "expected " + HEADER.size() + " fields, got " + r.fields().size()));
            return null;
        }
        int before = bad.size();
        String dateText = r.fields().get(0);
        String description = r.fields().get(1);
        String creditText = r.fields().get(2);
        String debitText = r.fields().get(3);
        String balanceText = r.fields().get(4);

        LocalDate date = null;
        try {
            date = LocalDate.parse(dateText, DATE);
        } catch (DateTimeParseException _) {
            bad.add(new BadRow(file, line, "Date", dateText, "expected dd/mm/yyyy"));
        }
        Long credit = Cents.optional(file, line, "Credit", creditText, bad);
        Long debit = Cents.optional(file, line, "Debit", debitText, bad);
        Long balance = balanceText.isEmpty() ? null : Cents.optional(file, line, "Balance", balanceText, bad);
        if (balanceText.isEmpty()) {
            bad.add(new BadRow(file, line, "Balance", balanceText, "balance is required"));
        }
        if (creditText.isEmpty() && debitText.isEmpty()) {
            bad.add(new BadRow(file, line, "Credit/Debit", "", "row has neither credit nor debit"));
        }
        if (credit != null && credit < 0) {
            bad.add(new BadRow(file, line, "Credit", creditText, "credit must not be negative"));
        }
        if (debit != null && debit > 0) {
            bad.add(new BadRow(file, line, "Debit", debitText, "debit must not be positive (ING debits are already negative)"));
        }
        if (bad.size() > before) {
            return null;
        }
        long amount = Math.addExact(credit == null ? 0 : credit, debit == null ? 0 : debit);
        Matcher m = RECEIPT.matcher(description);
        String receipt = m.find() ? m.group(2) : null;
        return new Candidate("row-" + line, accountRef, date, amount, description, balance, receipt,
            null, null, SOURCE_TYPE, Provenance.BANK);
    }

}
