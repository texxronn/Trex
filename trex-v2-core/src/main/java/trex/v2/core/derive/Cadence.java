package trex.v2.core.derive;

import java.util.Locale;

/**
 * How often a commitment expects to recur (V2-COMMITMENTS-PLAN.md §2.1): the seven regular
 * buckets detection measures, plus {@code irregular}.
 *
 * <p>{@code irregular} is declared only — detection never proves it — and generates no predicted
 * dates: each matching fact becomes an occurrence at its own date, so it is never dormant, missed
 * or in arrears (§2.5, §10.2).
 */
public enum Cadence {
    WEEKLY(7),
    FORTNIGHTLY(14),
    MONTHLY(30),
    BIMONTHLY(61),
    QUARTERLY(91),
    SEMIANNUAL(182),
    ANNUAL(365),
    IRREGULAR(0);

    private final int days;

    Cadence(int days) {
        this.days = days;
    }

    /**
     * The nominal period in days — the bucket a detected gap is classified against. Measured on
     * the fixture, the median-gap histogram spikes exactly at 7, 14, 30, 61, 91, 182 and 365
     * (V2-COMMITMENTS-PLAN.md §2.3.4). {@code irregular} has no period: {@code 0}.
     */
    public int days() {
        return days;
    }

    /** True for the seven calendar buckets; {@code irregular} is tracked by observation only. */
    public boolean regular() {
        return this != IRREGULAR;
    }

    /** The wire and derived-table form, e.g. {@code monthly}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
