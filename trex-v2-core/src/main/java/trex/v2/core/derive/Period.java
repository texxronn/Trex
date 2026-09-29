package trex.v2.core.derive;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ISO-8601 period keys used by {@code USER_ACK} (V2-PROPOSAL.md §9.4): {@code 2026} (year),
 * {@code 2026-Q3} (quarter), {@code 2026-09} (month), {@code 2026-W39} (week). Any user may close
 * any grain; cadence suggests, it never gates.
 */
public final class Period {

    private static final Pattern YEAR = Pattern.compile("\\d{4}");
    private static final Pattern QUARTER = Pattern.compile("\\d{4}-Q[1-4]");
    private static final Pattern MONTH = Pattern.compile("\\d{4}-\\d{2}");
    private static final Pattern WEEK = Pattern.compile("\\d{4}-W\\d{2}");

    private Period() {}

    public static boolean contains(String period, LocalDate date) {
        if (YEAR.matcher(period).matches()) {
            return date.getYear() == Integer.parseInt(period);
        }
        if (QUARTER.matcher(period).matches()) {
            int year = Integer.parseInt(period.substring(0, 4));
            int quarter = period.charAt(6) - '0';
            return date.getYear() == year && (date.getMonthValue() - 1) / 3 + 1 == quarter;
        }
        if (MONTH.matcher(period).matches()) {
            return YearMonth.from(date).toString().equals(period);
        }
        if (WEEK.matcher(period).matches()) {
            return weekKey(date).equals(period);
        }
        throw new IllegalArgumentException("unrecognised period '" + period + "' "
            + "(expected 2026, 2026-Q3, 2026-09 or 2026-W39)");
    }

    /** The ISO week key for a date, e.g. {@code 2026-W39}. */
    public static String weekKey(LocalDate date) {
        WeekFields wf = WeekFields.ISO;
        int week = date.get(wf.weekOfWeekBasedYear());
        int year = date.get(wf.weekBasedYear());
        return String.format(Locale.ROOT, "%04d-W%02d", year, week);
    }
}
