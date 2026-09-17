package trex.core;

import java.time.LocalDate;

/** Occurrence signature (content-hash banks). SPEC §2.3. */
public record Sig(String accountRef, LocalDate date, long amount, String rawDescription) {

    public static Sig of(Candidate c) {
        return new Sig(c.accountRef(), c.date(), c.amount(), c.rawDescription());
    }
}
