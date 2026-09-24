package trex.grid;

import trex.category.CategoryRules;
import trex.category.Categorizer;
import trex.core.CanonicalEvent;
import trex.core.Confidence;
import trex.core.EventState;
import trex.core.Flag;
import trex.core.Provenance;
import trex.core.TypeHint;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

final class Lines {

    private Lines() {}

    /** Small rules file written against {@link #sample()}; see src/test/resources. */
    static Categorizer categorizer() {
        return CategoryRules.load(Path.of("src", "test", "resources", "grid-categories.yaml"));
    }

    static CanonicalEvent line(long n, String id, String account, String date, long amount, String raw, EventState state) {
        return new CanonicalEvent(n, id, account, null, account.startsWith("bw") ? "USD" : "AUD", LocalDate.parse(date), amount,
            1000, raw.strip(), raw, amount < 0 ? TypeHint.WITHDRAWAL : TypeHint.DEPOSIT, null, null, null, state, null,
            List.of(), Provenance.BANK, "test", null, null, null, null, null, null, Instant.parse("2026-07-01T00:00:00Z"));
    }

    static CanonicalEvent transfer(long n, String id, String from, String to, String date, long amount, List<String> legs) {
        return new CanonicalEvent(n, id, from, to, "AUD", LocalDate.parse(date), amount, 0, "Fast Transfer", "Fast Transfer",
            TypeHint.TRANSFER, id, legs, null, EventState.MATCHED, Confidence.HIGH, List.of(), Provenance.BANK, "test",
            null, null, null, null, null, "note", Instant.parse("2026-07-01T00:00:00Z"));
    }

    /**
     * n1 a HELD ing -500 (06-02) · n2 b EXTERNAL cba +1200 (06-01) · n3 c EXTERNAL ing -300 "Coffee" (06-03)
     * n4 d HELD cba +500 (06-02) · n5 a MATCHED · n6 d MATCHED · n7 TRF ing→cba 500 · n8 c flagged dup
     * n9 e EXTERNAL bw-usd -999 "coffee beans" (06-05)
     */
    static GridData sample() {
        CanonicalEvent a = line(1, "a", "ing", "2026-06-02", -500, "Fast Transfer to CBA", EventState.HELD);
        CanonicalEvent b = line(2, "b", "cba", "2026-06-01", 1200, "Salary", EventState.EXTERNAL);
        CanonicalEvent c = line(3, "c", "ing", "2026-06-03", -300, "Coffee", EventState.EXTERNAL);
        CanonicalEvent d = line(4, "d", "cba", "2026-06-02", 500, "Transfer from ING", EventState.HELD);
        LinesFold fold = new LinesFold();
        for (CanonicalEvent l : List.of(a, b, c, d,
            a.reappend(5, EventState.MATCHED, List.of(), null),
            d.reappend(6, EventState.MATCHED, List.of(), null),
            transfer(7, "TRF-x", "ing", "cba", "2026-06-02", 500, List.of("a", "d")),
            c.reappend(8, EventState.EXTERNAL, List.of(Flag.POTENTIAL_DUP), null),
            line(9, "e", "bw-usd", "2026-06-05", -999, "coffee beans", EventState.EXTERNAL))) {
            fold.apply(l);
        }
        return fold.snapshot();
    }
}
