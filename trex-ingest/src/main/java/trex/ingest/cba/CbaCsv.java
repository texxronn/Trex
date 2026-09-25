package trex.ingest.cba;

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

/**
 * Source type {@code cba-csv}: CommBank CSV → candidates (SPEC §4). All CommBank-specific
 * knowledge lives here.
 * <p>
 * Two things differ from the other source types:
 * <ul>
 *   <li><b>No header row.</b> The first line is data, so the shape is the validation: four
 *       fields, a parsable date, exact cents. A file of another type fails on its first row
 *       rather than being half-read.</li>
 *   <li><b>The amount is already signed</b> and explicitly prefixed ({@code -75.00},
 *       {@code +1000.00}), so there are no credit/debit columns to combine.</li>
 * </ul>
 * No row carries a receipt, so every transaction takes content-hash identity (§2.5).
 */
public final class CbaCsv {

    public static final String SOURCE_TYPE = "cba-csv";

    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

    private static final int FIELDS = 4;
    private static final int DATE_COL = 0;
    private static final int AMOUNT_COL = 1;
    private static final int DESCRIPTION_COL = 2;
    private static final int BALANCE_COL = 3;

    private CbaCsv() {}

    public static Parsed parse(Path file, String accountRef) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8), file.getFileName().toString(), accountRef);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Parsed parse(String content, String fileName, String accountRef) {
        if (content.startsWith("﻿")) {
            content = content.substring(1);
        }
        List<Csv.Record> records;
        try {
            records = Csv.parse(content);
        } catch (Csv.CsvException e) {
            return new Parsed(List.of(), List.of(new BadRow(fileName, e.line(), "-", "", e.getMessage())));
        }

        List<BadRow> bad = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        for (Csv.Record r : records) {
            if (r.fields().size() == 1 && r.fields().getFirst().isEmpty()) {
                continue;   // blank line
            }
            Candidate c = row(fileName, accountRef, r, bad);
            if (c != null) {
                candidates.add(c);
            }
        }
        if (candidates.isEmpty() && bad.isEmpty()) {
            bad.add(new BadRow(fileName, 1, "-", "", "no rows"));
        }
        return new Parsed(bad.isEmpty() ? List.copyOf(candidates) : List.of(), List.copyOf(bad));
    }

    private static Candidate row(String file, String accountRef, Csv.Record r, List<BadRow> bad) {
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
        } catch (DateTimeParseException _) {
            // The mistake someone will actually make: feeding a file that has a header.
            String reason = line == 1 && String.join(",", r.fields()).contains("Date")
                ? "looks like a header; cba-csv expects none (the first line is data)"
                : "expected dd/mm/yyyy";
            bad.add(new BadRow(file, line, "date", dateText, reason));
        }
        Long amount = amountText.isEmpty() ? null : Cents.optional(file, line, "amount", amountText, bad);
        if (amountText.isEmpty()) {
            bad.add(new BadRow(file, line, "amount", amountText, "amount is required"));
        }
        Long balance = balanceText.isEmpty() ? null : Cents.optional(file, line, "balance", balanceText, bad);
        if (balanceText.isEmpty()) {
            bad.add(new BadRow(file, line, "balance", balanceText, "balance is required"));
        }
        if (bad.size() > before) {
            return null;
        }
        // Already signed in the file (-75.00 / +1000.00): nothing to combine, nothing to negate.
        return new Candidate("row-" + line, accountRef, date, amount, description, balance, null,
            null, null, SOURCE_TYPE, Provenance.BANK);
    }
}
