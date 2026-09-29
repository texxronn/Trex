package trex.v2.ingest.cba;

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

/**
 * Source type {@code cba-csv}: CommBank CSV → fact drafts. Two things differ: <b>no header row</b>
 * (the first line is data, so the shape is the validation), and the amount is <b>already signed</b>
 * ({@code -75.00}, {@code +1000.00}), so there are no credit/debit columns. No row carries a
 * receipt, so every row takes content-hash identity.
 */
public final class CbaCsv implements SourceAdapter {

    public static final String SOURCE_TYPE = "cba-csv";

    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

    private static final int FIELDS = 4;
    private static final int DATE_COL = 0;
    private static final int AMOUNT_COL = 1;
    private static final int DESCRIPTION_COL = 2;
    private static final int BALANCE_COL = 3;

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
        List<BadRow> bad = new ArrayList<>();
        List<FactDraft> drafts = new ArrayList<>();
        for (Csv.Record r : records) {
            if (r.fields().size() == 1 && r.fields().getFirst().isEmpty()) {
                continue;   // blank line
            }
            FactDraft draft = row(fileName, accountRef, r, bad);
            if (draft != null) {
                drafts.add(draft);
            }
        }
        if (drafts.isEmpty() && bad.isEmpty()) {
            bad.add(new BadRow(fileName, 1, "-", "", "no rows"));
        }
        return new Parsed(bad.isEmpty() ? List.copyOf(drafts) : List.of(), List.copyOf(bad), List.of());
    }

    private static FactDraft row(String file, String accountRef, Csv.Record r, List<BadRow> bad) {
        int line = r.line();
        if (r.fields().size() != FIELDS) {
            bad.add(new BadRow(file, line, "-", String.join(",", r.fields()),
                "expected " + FIELDS + " fields (date, amount, description, balance), got " + r.fields().size()));
            return null;
        }
        int before = bad.size();
        String dateText = r.fields().get(DATE_COL);
        String amountText = r.fields().get(AMOUNT_COL);
        String description = r.fields().get(DESCRIPTION_COL);
        String balanceText = r.fields().get(BALANCE_COL);

        LocalDate date = null;
        try {
            date = LocalDate.parse(dateText, DATE);
        } catch (DateTimeParseException e) {
            String reason = line == 1 && String.join(",", r.fields()).contains("Date")
                ? "looks like a header; cba-csv expects none (the first line is data)"
                : "expected dd/mm/yyyy";
            bad.add(new BadRow(file, line, "date", dateText, reason));
        }
        Long amount = amountText.isBlank() ? null : amount(file, line, "amount", amountText, bad);
        if (amountText.isBlank()) {
            bad.add(new BadRow(file, line, "amount", amountText, "amount is required"));
        }
        Long balance = balanceText.isBlank() ? null : amount(file, line, "balance", balanceText, bad);
        if (balanceText.isBlank()) {
            bad.add(new BadRow(file, line, "balance", balanceText, "balance is required"));
        }
        if (bad.size() > before) {
            return null;
        }
        return new FactDraft(accountRef, date, amount, balance, description, null, Observation.POSTED,
            SOURCE_TYPE, Provenance.BANK, null, null, null);
    }

    private static Long amount(String file, int line, String field, String text, List<BadRow> bad) {
        try {
            return Cents.parse(text);
        } catch (IllegalArgumentException e) {
            bad.add(new BadRow(file, line, field, text, e.getMessage()));
            return null;
        }
    }
}
