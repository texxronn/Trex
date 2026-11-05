package trex.v2.hub;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How often the idle hub forces a full derive to advance {@code asOf} (V2-ASOF-CADENCE-PLAN.md R-2).
 *
 * <p>Every {@code asOf} read in derive truncates to the UTC date, so a derive at 10:01 and one at
 * 10:02 produce identical output; a timer finer than the day buys nothing but CPU and index churn.
 * Hence the default {@link #DAY}. Finer cadences stay available (and {@code off}) for the odd
 * deployment that wants them.
 *
 * <p>Every cadence is one rule: an interval in epoch millis, due when now has crossed a UTC-aligned
 * boundary since the last full derive. {@code day} is simply the 24 h interval (epoch millis are
 * UTC, so its boundary is UTC midnight); {@code 0} means never. The check is pure: instants are
 * passed in, there is no ambient clock.
 *
 * @param intervalMs the aligned interval in millis, or 0 for off
 * @param label      the canonical text, for the startup log line
 */
public record AsOfCadence(long intervalMs, String label) {

    public AsOfCadence {
        if (intervalMs < 0) {
            throw new IllegalArgumentException("intervalMs must be >= 0, got " + intervalMs);
        }
    }

    private static final long DAY_MS = 86_400_000L;
    private static final Pattern FORM = Pattern.compile("(\\d+)([mh])");

    public static final AsOfCadence DAY = new AsOfCadence(DAY_MS, "day");
    public static final AsOfCadence OFF = new AsOfCadence(0, "off");

    /** Parse {@code day}, {@code off}, {@code <n>m} or {@code <n>h}; null or blank is {@code day}. */
    public static AsOfCadence parse(String text) {
        if (text == null || text.isBlank()) {
            return DAY;
        }
        String v = text.strip().toLowerCase(Locale.ROOT);
        if (v.equals("day")) {
            return DAY;
        }
        if (v.equals("off")) {
            return OFF;
        }
        Matcher m = FORM.matcher(v);
        if (m.matches()) {
            try {
                long n = Long.parseLong(m.group(1));
                long unit = m.group(2).equals("m") ? 60_000L : 3_600_000L;
                if (n >= 1 && n <= Long.MAX_VALUE / unit) {
                    return new AsOfCadence(n * unit, n + m.group(2));
                }
            } catch (NumberFormatException ignored) {
                // an absurdly long digit string: falls through to the refusal below
            }
        }
        throw new IllegalArgumentException(
            "asOf refresh must be day, off, <n>m or <n>h (got '" + text + "')");
    }

    /** A plain millisecond interval (the legacy long-taking constructors); 0 is off. */
    public static AsOfCadence everyMs(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("refreshAsOfByMs must be >= 0");
        }
        return ms == 0 ? OFF : new AsOfCadence(ms, ms + "ms");
    }

    /** Whether a timer derive is owed: now is in a different aligned interval than the last derive. */
    public boolean due(Instant lastFullDerive, Instant now) {
        if (intervalMs == 0) {
            return false;
        }
        return Math.floorDiv(now.toEpochMilli(), intervalMs)
            != Math.floorDiv(lastFullDerive.toEpochMilli(), intervalMs);
    }

    @Override
    public String toString() {
        return label;
    }
}
