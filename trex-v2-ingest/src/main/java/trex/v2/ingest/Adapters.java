package trex.v2.ingest;

import trex.v2.ingest.bw.BwCsv;
import trex.v2.ingest.ing.IngCsv;

import java.util.List;

/** The source adapters, by source type (V2-PROPOSAL.md §12.1). Adding one is adding a case. */
public final class Adapters {

    private Adapters() {}

    public static SourceAdapter byType(String sourceType) {
        return switch (sourceType) {
            case IngCsv.SOURCE_TYPE -> new IngCsv();
            case BwCsv.SOURCE_TYPE -> new BwCsv();
            default -> throw new IllegalArgumentException("unknown source type '" + sourceType
                + "' (known: " + types() + ")");
        };
    }

    public static List<String> types() {
        return List.of(IngCsv.SOURCE_TYPE, BwCsv.SOURCE_TYPE);
    }
}
