package trex.v2.ingest.ing;

import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.ingest.BadRow;
import trex.v2.ingest.Cents;
import trex.v2.ingest.Csv;
import trex.v2.ingest.FactDraft;
import trex.v2.ingest.Parsed;
import trex.v2.ingest.SourceAdapter;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source type {@code ing-csv}: ING CSV → fact drafts. All ING-specific knowledge lives here. */
public final class IngCsv implements SourceAdapter {

    public static final String SOURCE_TYPE = "ing-csv";
    static final List<String> HEADER = List.of("Date", "Description", "Credit", "Debit", "Balance");

    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern RECEIPT = Pattern.compile("Receipt (No )?(\\d+)");

    @Override
    public String sourceType() {
        return SOURCE_TYPE;
    }

    @Override
    public Parsed parse(byte[] content, String fileName, String accountRef) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<Csv.Record> records;
        try {
            records = Csv.parse(text);
        } catch (Csv.CsvException e) {
            return new Parsed(List.of(), List.of(new BadRow(fileName, e.line(), "-", "", e.getMessage())),
                List.of());
        }
        if (records.isEmpty() || !records.getFirst().fields().equals(HEADER)) {
            String got = records.isEmpty() ? "" : String.join(",", records.getFirst().fields());
            return new Parsed(List.of(), List.of(new BadRow(fileName, 1, "header", got,
                "expected header " + String.join(",", HEADER))), List.of());
        }
        List<BadRow> bad = new ArrayList<>();
        List<FactDraft> drafts = new ArrayList<>();
        for (Csv.Record r : records.subList(1, records.size())) {
            if (r.fields().size() == 1 && r.fields().getFirst().isEmpty()) {
                continue;   // blank line
            }
            FactDraft draft = row(fileName, accountRef, r, bad);
            if (draft != null) {
                drafts.add(draft);
            }
        }
        return new Parsed(bad.isEmpty() ? List.copyOf(drafts) : List.of(), List.copyOf(bad), List.of());
    }

    private static FactDraft row(String file, String accountRef, Csv.Record r, List<BadRow> bad) {
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
        } catch (DateTimeParseException e) {
            bad.add(new BadRow(file, line, "Date", dateText, "expected dd/mm/yyyy"));
        }
        Long credit = amount(file, line, "Credit", creditText, bad);
        Long debit = amount(file, line, "Debit", debitText, bad);
        Long balance = amount(file, line, "Balance", balanceText, bad);
        if (balanceText.isBlank()) {
            bad.add(new BadRow(file, line, "Balance", balanceText, "balance is required"));
        }
        if (creditText.isBlank() && debitText.isBlank()) {
            bad.add(new BadRow(file, line, "Credit/Debit", "", "row has neither credit nor debit"));
        }
        if (credit != null && credit < 0) {
            bad.add(new BadRow(file, line, "Credit", creditText, "credit must not be negative"));
        }
        if (debit != null && debit > 0) {
            bad.add(new BadRow(file, line, "Debit", debitText,
                "debit must not be positive (ING debits are already negative)"));
        }
        if (bad.size() > before) {
            return null;
        }
        long amount = Math.addExact(credit == null ? 0 : credit, debit == null ? 0 : debit);
        Matcher m = RECEIPT.matcher(description);
        String receipt = m.find() ? m.group(2) : null;
        return new FactDraft(accountRef, date, amount, balance, description, receipt,
            Observation.POSTED, SOURCE_TYPE, Provenance.BANK, null, null, null);
    }

    private static Long amount(String file, int line, String field, String text, List<BadRow> bad) {
        if (text.isBlank()) {
            return null;
        }
        try {
            return Cents.parse(text);
        } catch (IllegalArgumentException e) {
            bad.add(new BadRow(file, line, field, text, e.getMessage()));
            return null;
        }
    }
}
