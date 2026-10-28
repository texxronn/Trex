package trex.v2.ingest;

import trex.v2.ingest.bw.BwCsv;
import trex.v2.ingest.cba.CbaCsv;
import trex.v2.ingest.cba.CbaPdf;
import trex.v2.ingest.ing.IngCsv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The source adapters, by source type (V2-PROPOSAL.md §12.1). Adding one is adding a case. */
public final class Adapters {

    private Adapters() {}

    public static SourceAdapter byType(String sourceType) {
        return switch (sourceType) {
            case IngCsv.SOURCE_TYPE -> new IngCsv();
            case BwCsv.SOURCE_TYPE -> new BwCsv();
            case CbaCsv.SOURCE_TYPE -> new CbaCsv();
            case CbaPdf.SOURCE_TYPE -> new CbaPdf();
            default -> throw new IllegalArgumentException("unknown source type '" + sourceType
                + "' (known: " + types() + ")");
        };
    }

    public static List<String> types() {
        return List.of(IngCsv.SOURCE_TYPE, BwCsv.SOURCE_TYPE, CbaCsv.SOURCE_TYPE, CbaPdf.SOURCE_TYPE);
    }

    /**
     * The source types whose adapter parses the whole file with no bad row and at least one row —
     * the same test an ingest applies, so a detected type is one the ingest will accept. Each shape
     * rejects the others at its first line (ING and BW by their headers, CBA CSV by having none,
     * the PDF by its bytes), so a real statement gives exactly one; none or several means the
     * person picks. An adapter that throws (CbaPdf on bytes that are not a PDF) is "not this type".
     */
    public static List<String> detect(byte[] content, String fileName) {
        List<String> found = new ArrayList<>();
        for (String type : types()) {
            // pdfbox logs a warning for every non-PDF it is handed; a CSV never starts with the magic.
            if (type.equals(CbaPdf.SOURCE_TYPE) && !startsWith(content, PDF_MAGIC)) {
                continue;
            }
            Parsed parsed;
            try {
                // The guard compares content, never accountRef, so the placeholder reaches nothing.
                parsed = byType(type).parse(content, fileName, "?");
            } catch (RuntimeException e) {
                continue;
            }
            if (parsed.clean() && !parsed.candidates().isEmpty()) {
                found.add(type);
            }
        }
        return found;
    }

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    private static boolean startsWith(byte[] content, byte[] prefix) {
        return content.length >= prefix.length
            && Arrays.equals(content, 0, prefix.length, prefix, 0, prefix.length);
    }
}
