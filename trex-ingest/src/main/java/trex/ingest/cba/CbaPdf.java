package trex.ingest.cba;

import trex.core.Candidate;
import trex.core.Provenance;
import trex.ingest.BadRow;
import trex.ingest.Cents;
import trex.ingest.Parsed;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source type {@code cba-pdf}: CommBank's "Transaction Summary" PDF → candidates (SPEC §4).
 * For one of these accounts it is the only export the bank offers.
 * <p>
 * The extraction and normalisation rules here are <b>frozen</b>, in the same sense as §2.4:
 * CommBank rows carry no receipt, so the description is hashed into {@code external_id}, and a
 * PDF has no verbatim byte string to hash. Changing how lines are joined re-mints every id for
 * this source type. What makes it safe to hash at all is that two unrelated extractors —
 * PDFBox and poppler's {@code pdftotext} — reproduce the same account's CSV descriptions
 * byte-for-byte; the golden-file test is what keeps that true across upgrades.
 */
public final class CbaPdf {

    public static final String SOURCE_TYPE = "cba-pdf";

    /** {@code 01 Jan 2026  Debit Excess Interest  -$0.09  $1,296.21} */
    private static final Pattern TRANSACTION = Pattern.compile(
        "^(\\d{2} [A-Za-z]{3} \\d{4})\\s+(.+?)\\s+(-?\\$[\\d,]+\\.\\d{2})\\s+(-?\\$[\\d,]+\\.\\d{2})$");

    /** Everything from here to the next table header is page furniture, footer included. */
    private static final Pattern FOOTER = Pattern.compile("^Created \\d{2}/\\d{2}/\\d{2}");
    private static final Pattern TABLE_HEADER = Pattern.compile("^Date\\s+Transaction details");

    /** English month names, never the platform default: the machine must not change the parse. */
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private CbaPdf() {}

    public static Parsed parse(Path file, String accountRef) {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return parse(stripper.getText(document), file.getFileName().toString(), accountRef);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** Takes already-extracted text, so the parsing rules can be tested without a PDF. */
    public static Parsed parse(String text, String fileName, String accountRef) {
        List<BadRow> bad = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        List<String> descriptions = new ArrayList<>();   // parallel to candidates, for continuations

        boolean beforeFirstTable = true;
        boolean inFurniture = false;
        int line = 0;
        for (String raw : text.split("\n", -1)) {
            line++;
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (TABLE_HEADER.matcher(trimmed).find()) {
                beforeFirstTable = false;
                inFurniture = false;
                continue;
            }
            if (FOOTER.matcher(trimmed).find()) {
                inFurniture = true;
                continue;
            }
            if (beforeFirstTable || inFurniture) {
                continue;   // address block, account summary, page footers
            }

            Matcher m = TRANSACTION.matcher(trimmed);
            if (m.matches()) {
                Candidate c = transaction(fileName, accountRef, line, m, bad);
                if (c != null) {
                    candidates.add(c);
                    descriptions.add(c.rawDescription());
                }
                continue;
            }
            // Anything else inside the table continues the description of the row above it.
            if (!candidates.isEmpty()) {
                int last = candidates.size() - 1;
                descriptions.set(last, descriptions.get(last) + " " + trimmed);
                candidates.set(last, withDescription(candidates.get(last), descriptions.get(last)));
            }
        }

        if (candidates.isEmpty() && bad.isEmpty()) {
            bad.add(new BadRow(fileName, 1, "-", "", "no transactions found — is this a CommBank Transaction Summary?"));
        }
        return new Parsed(bad.isEmpty() ? List.copyOf(candidates) : List.of(), List.copyOf(bad));
    }

    private static Candidate transaction(String file, String accountRef, int line, Matcher m, List<BadRow> bad) {
        int before = bad.size();
        LocalDate date = null;
        try {
            date = LocalDate.parse(m.group(1), DATE);
        } catch (DateTimeParseException _) {
            bad.add(new BadRow(file, line, "date", m.group(1), "expected dd MMM yyyy"));
        }
        Long amount = money(file, line, "amount", m.group(3), bad);
        Long balance = money(file, line, "balance", m.group(4), bad);
        if (bad.size() > before) {
            return null;
        }
        return new Candidate("row-" + line, accountRef, date, amount, normalise(m.group(2)), balance, null,
            null, null, SOURCE_TYPE, Provenance.BANK);
    }

    /** {@code -$1,234.56} → cents. The $ and separators are stripped here so {@link Cents} stays strict. */
    private static Long money(String file, int line, String column, String text, List<BadRow> bad) {
        String plain = text.replace("$", "").replace(",", "");
        try {
            return Cents.of(plain);
        } catch (NumberFormatException | ArithmeticException _) {
            bad.add(new BadRow(file, line, column, text, "not an exact amount in cents"));
            return null;
        }
    }

    /** Frozen (SPEC §4): collapse whitespace runs to one space and trim. */
    private static String normalise(String description) {
        return WHITESPACE.matcher(description).replaceAll(" ").strip();
    }

    private static Candidate withDescription(Candidate c, String description) {
        return new Candidate(c.candidateRef(), c.accountRef(), c.date(), c.amount(), normalise(description),
            c.balance(), c.receipt(), c.counterpartyBsb(), c.counterpartyAcct(), c.sourceType(), c.provenance());
    }
}
