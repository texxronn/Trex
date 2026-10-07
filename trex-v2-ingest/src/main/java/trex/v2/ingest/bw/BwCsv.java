package trex.v2.ingest.bw;

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
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;

/**
 * Source type {@code bw-csv}: BankWest CSV → fact drafts. The export writes debits positive in one
 * version and negative in another, so the sign is inferred per file and a file that mixes them is
 * rejected rather than guessed at (the amount is hashed into identity). A pending authorisation is
 * now recorded as a {@code pending} fact, not skipped (V2-PROPOSAL.md §12.3).
 */
public final class BwCsv implements SourceAdapter {

    public static final String SOURCE_TYPE = "bw-csv";
    private static final List<String> CHEQUE_LABELS = List.of("Cheque", "Cheque Number");
    private static final List<String> HEADER = List.of(
        "BSB Number", "Account Number", "Transaction Date", "Narration", "Cheque",
        "Debit", "Credit", "Balance", "Transaction Type");
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final String PENDING = "AUTHORISATION ONLY";

    private static final int DATE_COL = 2;
    private static final int NARRATION_COL = 3;
    private static final int DEBIT_COL = 5;
    private static final int CREDIT_COL = 6;
    private static final int BALANCE_COL = 7;

    private enum DebitSign { POSITIVE, NEGATIVE }

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
        if (records.isEmpty() || !isHeader(records.getFirst().fields())) {
            String got = records.isEmpty() ? "" : String.join(",", records.getFirst().fields());
            return new Parsed(List.of(), List.of(new BadRow(fileName, 1, "header", got,
                "expected header " + String.join(",", HEADER) + " (fifth column may be 'Cheque Number')")),
                List.of());
        }
        DebitSign debitSign;
        try {
            debitSign = debitSign(records.subList(1, records.size()));
        } catch (IllegalArgumentException e) {
            return new Parsed(List.of(), List.of(new BadRow(fileName, 1, "Debit", "", e.getMessage())), List.of());
        }
        List<BadRow> bad = new ArrayList<>();
        List<FactDraft> drafts = new ArrayList<>();
        for (Csv.Record r : records.subList(1, records.size())) {
            if (r.fields().size() == 1 && r.fields().getFirst().isEmpty()) {
                continue;
            }
            FactDraft draft = row(fileName, accountRef, r, debitSign, bad);
            if (draft != null) {
                drafts.add(draft);
            }
        }
        return new Parsed(bad.isEmpty() ? List.copyOf(drafts) : List.of(), List.copyOf(bad), List.of());
    }

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

    private static DebitSign debitSign(List<Csv.Record> body) {
        boolean positive = false;
        boolean negative = false;
        for (Csv.Record r : body) {
            if (r.fields().size() != HEADER.size()) {
                continue;
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

    private static FactDraft row(String file, String accountRef, Csv.Record r, DebitSign debitSign,
                                 List<BadRow> bad) {
        int line = r.line();
        if (r.fields().size() != HEADER.size()) {
            bad.add(new BadRow(file, line, "-", String.join(",", r.fields()),
                "expected " + HEADER.size() + " fields, got " + r.fields().size()));
            return null;
        }
        int before = bad.size();
        String dateText = r.fields().get(DATE_COL);
        String narration = r.fields().get(NARRATION_COL);
        String debitText = r.fields().get(DEBIT_COL);
        String creditText = r.fields().get(CREDIT_COL);
        String balanceText = r.fields().get(BALANCE_COL);

        LocalDate date = null;
        try {
            date = LocalDate.parse(dateText, DATE);
        } catch (RuntimeException e) {
            bad.add(new BadRow(file, line, "Transaction Date", dateText, "expected dd/mm/yyyy"));
        }
        Long debit = amount(file, line, "Debit", debitText, bad);
        Long credit = amount(file, line, "Credit", creditText, bad);
        Long balance = amount(file, line, "Balance", balanceText, bad);
        if (balanceText.isBlank()) {
            bad.add(new BadRow(file, line, "Balance", balanceText, "balance is required"));
        }
        if (debitText.isBlank() && creditText.isBlank()) {
            bad.add(new BadRow(file, line, "Debit/Credit", "", "row has neither debit nor credit"));
        }
        if (!debitText.isBlank() && !creditText.isBlank()) {
            bad.add(new BadRow(file, line, "Debit/Credit", debitText + "/" + creditText,
                "row has both a debit and a credit"));
        }
        if (credit != null && credit < 0) {
            bad.add(new BadRow(file, line, "Credit", creditText, "credit must not be negative"));
        }
        if (debit != null && (debitSign == DebitSign.POSITIVE ? debit < 0 : debit > 0)) {
            bad.add(new BadRow(file, line, "Debit", debitText,
                "debit sign does not match the rest of the file"));
        }
        if (bad.size() > before) {
            return null;
        }
        long signedDebit = debit == null ? 0 : (debitSign == DebitSign.POSITIVE ? -debit : debit);
        long value = Math.addExact(credit == null ? 0 : credit, signedDebit);
        Observation observation = narration.stripLeading().toUpperCase().startsWith(PENDING)
            ? Observation.PENDING : Observation.POSTED;
        return new FactDraft(accountRef, date, value, balance, narration, null,
            observation, SOURCE_TYPE, Provenance.BANK, null, null, null);
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
