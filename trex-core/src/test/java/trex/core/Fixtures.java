package trex.core;

import java.time.LocalDate;
import java.util.List;

/** Hand-built candidate fixtures for stage-1 tests (ING-slice versions arrive in stage 5). */
final class Fixtures {

    private Fixtures() {}

    static Candidate candidate(String ref, String account, String date, long amount, String rawDescription,
                               long balance, String receipt) {
        return new Candidate(ref, account, LocalDate.parse(date), amount, rawDescription, balance, receipt,
            null, null, "test", Provenance.BANK);
    }

    /** Natural-key data: every row has a distinct receipt. */
    static List<Candidate> naturalKeyBatch() {
        return List.of(
            candidate("row-2", "ing-savings", "2026-06-01", -1250, "Woolworths Receipt 1001", 98750, "1001"),
            candidate("row-3", "ing-savings", "2026-06-01", -1250, "Woolworths Receipt 1002", 97500, "1002"),
            candidate("row-4", "ing-savings", "2026-06-02", 250000, "Salary Receipt 1003", 347500, "1003"),
            candidate("row-5", "ing-savings", "2026-06-03", -50000, "Fast Transfer to CBA Receipt 1004", 297500, "1004"));
    }

    /** Content-hash data: repeated signatures on the same day. */
    static List<Candidate> contentHashBatch() {
        return List.of(
            candidate("row-2", "cba-everyday", "2026-06-01", -450, "COFFEE CART", 10000, null),
            candidate("row-3", "cba-everyday", "2026-06-01", -450, "COFFEE CART", 9550, null),
            candidate("row-4", "cba-everyday", "2026-06-01", -450, "COFFEE CART", 9100, null),
            candidate("row-5", "cba-everyday", "2026-06-01", -900, "COFFEE CART", 8200, null),
            candidate("row-6", "cba-everyday", "2026-06-02", -450, "COFFEE CART", 7750, null),
            candidate("row-7", "cba-everyday", "2026-06-02", 50000, "Transfer from ING", 57750, null));
    }
}
