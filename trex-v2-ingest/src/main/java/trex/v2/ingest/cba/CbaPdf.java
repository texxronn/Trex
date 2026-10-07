package trex.v2.ingest.cba;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.ingest.BadRow;
import trex.v2.ingest.Cents;
import trex.v2.ingest.FactDraft;
import trex.v2.ingest.Parsed;
import trex.v2.ingest.SourceAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * Source type {@code cba-pdf}: CommBank's "Transaction Summary" PDF → fact drafts. The extraction
 * and normalisation rules are <b>frozen</b>: CommBank rows carry no receipt, so the description is
 * hashed into {@code externalId}, and a PDF has no verbatim byte string to hash. Changing how lines
 * are joined re-mints every id for this source type; the golden-file test keeps it stable.
 */
public final class CbaPdf implements SourceAdapter {

    public static final String SOURCE_TYPE = "cba-pdf";

    /** {@code 01 Jan 2026  Debit Excess Interest  -$0.09  $1,296.21} */
    private static final Pattern TRANSACTION = Pattern.compile(
        "^(\\d{2} [A-Za-z]{3} \\d{4})\\s+(.+?)\\s+(-?\\$[\\d,]+\\.\\d{2})\\s+(-?\\$[\\d,]+\\.\\d{2})$");

    private static final Pattern FOOTER = Pattern.compile("^Created \\d{2}/\\d{2}/\\d{2}");
    private static final Pattern TABLE_HEADER = Pattern.compile("^Date\\s+Transaction details");

    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Override
    public String sourceType() {
        return SOURCE_TYPE;
    }

    @Override
    public Parsed parse(byte[] content, String fileName, String accountRef) {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return parse(stripper.getText(document), fileName, accountRef);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + fileName, e);
        }
    }

    /** Takes already-extracted text, so the parsing rules are testable without a PDF. */
    public static Parsed parse(String text, String fileName, String accountRef) {
        List<BadRow> bad = new ArrayList<>();
        List<FactDraft> drafts = new ArrayList<>();
        List<String> descriptions = new ArrayList<>();

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
                continue;
            }
            Matcher m = TRANSACTION.matcher(trimmed);
            if (m.matches()) {
                FactDraft draft = transaction(fileName, accountRef, line, m, bad);
                if (draft != null) {
                    drafts.add(draft);
                    descriptions.add(draft.rawDescription());
                }
                continue;
            }
            // Anything else inside the table continues the description of the row above it.
            if (!drafts.isEmpty()) {
                int last = drafts.size() - 1;
                descriptions.set(last, normalise(descriptions.get(last) + " " + trimmed));
                drafts.set(last, withDescription(drafts.get(last), descriptions.get(last)));
            }
        }
        if (drafts.isEmpty() && bad.isEmpty()) {
            bad.add(new BadRow(fileName, 1, "-", "",
                "no transactions found — is this a CommBank Transaction Summary?"));
        }
        return new Parsed(bad.isEmpty() ? List.copyOf(drafts) : List.of(), List.copyOf(bad), List.of());
    }

    private static FactDraft transaction(String file, String accountRef, int line, Matcher m, List<BadRow> bad) {
        int before = bad.size();
        LocalDate date = null;
        try {
            date = LocalDate.parse(m.group(1), DATE);
        } catch (DateTimeParseException e) {
            bad.add(new BadRow(file, line, "date", m.group(1), "expected dd MMM yyyy"));
        }
        Long amount = money(file, line, "amount", m.group(3), bad);
        Long balance = money(file, line, "balance", m.group(4), bad);
        if (bad.size() > before) {
            return null;
        }
        return new FactDraft(accountRef, date, amount, balance, normalise(m.group(2)), null,
            Observation.POSTED, SOURCE_TYPE, Provenance.BANK, null, null, null);
    }

    /** {@code -$1,234.56} → cents. The $ and separators are stripped here so {@link Cents} stays strict. */
    private static Long money(String file, int line, String column, String text, List<BadRow> bad) {
        String plain = text.replace("$", "").replace(",", "");
        try {
            return Cents.parse(plain);
        } catch (IllegalArgumentException e) {
            bad.add(new BadRow(file, line, column, text, "not an exact amount in cents"));
            return null;
        }
    }

    /** Frozen: collapse whitespace runs to one space and trim. */
    private static String normalise(String description) {
        return WHITESPACE.matcher(description).replaceAll(" ").strip();
    }

    private static FactDraft withDescription(FactDraft d, String description) {
        return new FactDraft(d.accountRef(), d.date(), d.amount(), d.balance(), description, d.receipt(),
            d.observation(), d.sourceType(), d.provenance(), d.evidenceId(), d.parser(), d.ingestedAt());
    }
}
