package trex.ingest.bw;

import trex.core.account.Account;

import trex.core.Candidate;
import trex.core.Provenance;
import trex.ingest.BadRow;
import trex.ingest.Cents;
import trex.ingest.Csv;
import trex.ingest.Parsed;
import trex.ingest.SkippedRow;

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
 * Source type {@code bw-csv}: BankWest CSV → candidates (SPEC §4). All BankWest-specific
 * knowledge lives here.
 * <p>
 * Two things differ from {@code ing-csv} and both matter:
 * <ul>
 *   <li><b>Debit is positive</b> in this export, so {@code amount = credit − debit}. ING writes
 *       its debits already negative. Same shape of file, opposite convention.</li>
 *   <li><b>No receipts.</b> Every row takes content-hash identity, so {@code occ} and the
 *       day-atomic batching rule carry the weight here (§2.5).</li>
 * </ul>
 * The whole file is validated; any bad value makes the file invalid (nothing is sent).
 */
public final class BwCsv {

    public static final String SOURCE_TYPE = "bw-csv";

    /** BankWest ships two spellings of the fifth column; everything else is identical. */
    private static final List<String> CHEQUE_LABELS = List.of("Cheque", "Cheque Number");

    static final List<String> HEADER = List.of(
        "BSB Number", "Account Number", "Transaction Date", "Narration", "Cheque",
        "Debit", "Credit", "Balance", "Transaction Type");

    /**
     * Which way this export writes debits. BankWest has both: one export writes them positive,
     * another already negative. Assuming either would silently invert every debit in a file of
     * the other kind, and the amount is hashed into identity (§2.4), so it is inferred per file
     * and a file that mixes signs is rejected rather than guessed at.
     */
    private enum DebitSign { POSITIVE, NEGATIVE }

    /** BankWest's own marker for an authorisation that has not settled (SPEC §4 pending rows). */
    private static final String PENDING = "AUTHORISATION ONLY";

    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

    private static final int DATE_COL = 2;
    private static final int NARRATION_COL = 3;
    private static final int DEBIT_COL = 5;
    private static final int CREDIT_COL = 6;
    private static final int BALANCE_COL = 7;

    private BwCsv() {}

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
        if (records.isEmpty() || !isHeader(records.getFirst().fields())) {
            String got = records.isEmpty() ? "" : String.join(",", records.getFirst().fields());
            return new Parsed(List.of(), List.of(new BadRow(fileName, 1, "header", got,
                "expected header " + String.join(",", HEADER) + " (fifth column may be 'Cheque Number')")));
        }

        List<Csv.Record> body = records.subList(1, records.size());
        DebitSign debitSign;
        try {
            debitSign = debitSign(body);
        } catch (IllegalArgumentException e) {
            return new Parsed(List.of(), List.of(new BadRow(fileName, 1, "Debit", "", e.getMessage())));
        }

        List<BadRow> bad = new ArrayList<>();
        List<SkippedRow> skipped = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        for (Csv.Record r : body) {
            if (r.fields().size() == 1 && r.fields().getFirst().isEmpty()) {
                continue;   // blank line
            }
            if (r.fields().size() != HEADER.size()) {
                bad.add(new BadRow(fileName, r.line(), "-", String.join(",", r.fields()),
                    "expected " + HEADER.size() + " fields, got " + r.fields().size()));
                continue;
            }
            String narration = r.fields().get(NARRATION_COL);
            if (narration.stripLeading().toUpperCase().startsWith(PENDING)) {
                // Not an error: it settles later under different text, which is a different id.
                skipped.add(new SkippedRow(fileName, r.line(), "pending authorisation, not settled", narration));
                continue;
            }
            Candidate c = row(fileName, accountRef, r, debitSign, bad);
            if (c != null) {
                candidates.add(c);
            }
        }
        return new Parsed(bad.isEmpty() ? List.copyOf(candidates) : List.of(), List.copyOf(bad), List.copyOf(skipped));
    }

    /** Header check, tolerant only of the fifth column's two spellings. */
    private static boolean isHeader(List<String> fields) {
        if (fields.size() != HEADER.size() || !CHEQUE_LABELS.contains(fields.get(4))) {
            return false;
        }
        for (int i = 0; i < HEADER.size(); i++) {
            if (i != 4 && !HEADER.get(i).equals(fields.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** All debits positive, or all negative. Mixed is not a convention, it is a broken file. */
    private static DebitSign debitSign(List<Csv.Record> body) {
        boolean positive = false;
        boolean negative = false;
        for (Csv.Record r : body) {
            if (r.fields().size() != HEADER.size()) {
                continue;   // reported as a bad row later
            }
            String debit = r.fields().get(DEBIT_COL);
            if (debit.isEmpty() || debit.startsWith("0")) {
                continue;
            }
            if (debit.startsWith("-")) {
                negative = true;
            } else {
                positive = true;
            }
        }
        if (positive && negative) {
            throw new IllegalArgumentException(
                "file mixes positive and negative debits; one export writes them positive and another "
                    + "negative, and there is no sound reading of both in one file");
        }
        return negative ? DebitSign.NEGATIVE : DebitSign.POSITIVE;
    }

    private static Candidate row(String file, String accountRef, Csv.Record r, DebitSign debitSign, List<BadRow> bad) {
        int line = r.line();
        int before = bad.size();
        String dateText = r.fields().get(DATE_COL);
        String narration = r.fields().get(NARRATION_COL);
        String debitText = r.fields().get(DEBIT_COL);
        String creditText = r.fields().get(CREDIT_COL);
        String balanceText = r.fields().get(BALANCE_COL);

        LocalDate date = null;
        try {
            date = LocalDate.parse(dateText, DATE);
        } catch (DateTimeParseException _) {
            bad.add(new BadRow(file, line, "Transaction Date", dateText, "expected dd/mm/yyyy"));
        }
        Long debit = Cents.optional(file, line, "Debit", debitText, bad);
        Long credit = Cents.optional(file, line, "Credit", creditText, bad);
        Long balance = balanceText.isEmpty() ? null : Cents.optional(file, line, "Balance", balanceText, bad);
        if (balanceText.isEmpty()) {
            bad.add(new BadRow(file, line, "Balance", balanceText, "balance is required"));
        }
        if (debitText.isEmpty() && creditText.isEmpty()) {
            bad.add(new BadRow(file, line, "Debit/Credit", "", "row has neither debit nor credit"));
        }
        if (!debitText.isEmpty() && !creditText.isEmpty()) {
            bad.add(new BadRow(file, line, "Debit/Credit", debitText + "/" + creditText,
                "row has both a debit and a credit"));
        }
        // Credits are positive in both exports; only the debit column varies.
        if (credit != null && credit < 0) {
            bad.add(new BadRow(file, line, "Credit", creditText, "credit must not be negative"));
        }
        if (debit != null && (debitSign == DebitSign.POSITIVE ? debit < 0 : debit > 0)) {
            bad.add(new BadRow(file, line, "Debit", debitText,
                "debit sign does not match the rest of the file (which writes debits "
                    + (debitSign == DebitSign.POSITIVE ? "positive)" : "negative)")));
        }
        if (bad.size() > before) {
            return null;
        }
        long signedDebit = debit == null ? 0 : (debitSign == DebitSign.POSITIVE ? -debit : debit);
        long amount = Math.addExact(credit == null ? 0 : credit, signedDebit);
        // No receipt anywhere in this format: identity is the content hash, with occ per batch.
        return new Candidate("row-" + line, accountRef, date, amount, narration, balance, null,
            null, null, SOURCE_TYPE, Provenance.BANK);
    }
}
