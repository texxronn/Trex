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
    private static final Pattern MONTH = Pattern.compile("\\d{4}-(?:0[1-9]|1[0-2])");
    private static final Pattern WEEK = Pattern.compile("\\d{4}-W(?:0[1-9]|[1-4]\\d|5[0-3])");

    private Period() {}

    /** An inclusive date range, the calendar span a period key names. */
    public record Range(LocalDate from, LocalDate to) {
        public boolean contains(LocalDate date) {
            return !date.isBefore(from) && !date.isAfter(to);
        }
    }

    /** The inclusive date range a period key covers. The only place the grains are spelled out. */
    public static Range bounds(String period) {
        if (YEAR.matcher(period).matches()) {
            int year = Integer.parseInt(period);
            return new Range(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        }
        if (QUARTER.matcher(period).matches()) {
            int year = Integer.parseInt(period.substring(0, 4));
            int quarter = period.charAt(6) - '0';
            LocalDate from = LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
            LocalDate to = from.plusMonths(2);
            return new Range(from, to.withDayOfMonth(to.lengthOfMonth()));
        }
        if (MONTH.matcher(period).matches()) {
            YearMonth month = YearMonth.parse(period);
            return new Range(month.atDay(1), month.atEndOfMonth());
        }
        if (WEEK.matcher(period).matches()) {
            // ISO week 1 is the week containing 4 January; week N starts 7*(N-1) days after its Monday.
            int year = Integer.parseInt(period.substring(0, 4));
            int week = Integer.parseInt(period.substring(6));
            LocalDate jan4 = LocalDate.of(year, 1, 4);
            LocalDate week1Monday = jan4.minusDays(jan4.getDayOfWeek().getValue() - 1);
            LocalDate from = week1Monday.plusWeeks(week - 1L);
            return new Range(from, from.plusDays(6));
        }
        throw new IllegalArgumentException("unrecognised period '" + period + "' "
            + "(expected 2026, 2026-Q3, 2026-09 or 2026-W39)");
    }

    public static boolean contains(String period, LocalDate date) {
        return bounds(period).contains(date);
    }

    /** The ISO week key for a date, e.g. {@code 2026-W39}. */
    public static String weekKey(LocalDate date) {
        WeekFields wf = WeekFields.ISO;
        int week = date.get(wf.weekOfWeekBasedYear());
        int year = date.get(wf.weekBasedYear());
        return String.format(Locale.ROOT, "%04d-W%02d", year, week);
    }
}
