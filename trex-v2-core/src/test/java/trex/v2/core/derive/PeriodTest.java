package trex.v2.core.derive;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Period#bounds} names the calendar span a period key covers; {@link Period#contains} is now
 * defined by it. These pin the grains and the ISO-week edge (week 1 can begin in the previous year).
 */
class PeriodTest {

    @Test
    void yearQuarterAndMonthBounds() {
        assertEquals(new Period.Range(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
            Period.bounds("2026"));
        assertEquals(new Period.Range(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30)),
            Period.bounds("2026-Q3"));
        assertEquals(new Period.Range(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)),
            Period.bounds("2026-09"));
        assertEquals(new Period.Range(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29)),
            Period.bounds("2024-02"));
    }

    @Test
    void dayIsASingleDayPeriod() {
        assertEquals(new Period.Range(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 1, 20)),
            Period.bounds("2026-01-20"));
        assertTrue(Period.contains("2026-01-20", LocalDate.of(2026, 1, 20)));
        assertFalse(Period.contains("2026-01-20", LocalDate.of(2026, 1, 21)));
    }

    @Test
    void weekIsMondayThroughSundayAndCanStartInThePriorYear() {
        Period.Range w1 = Period.bounds("2026-W01");
        assertEquals(DayOfWeek.MONDAY, w1.from().getDayOfWeek());
        assertEquals(DayOfWeek.SUNDAY, w1.to().getDayOfWeek());
        assertEquals(6, java.time.temporal.ChronoUnit.DAYS.between(w1.from(), w1.to()));
        // 2026-01-01 is a Thursday, so ISO week 1 starts on 2025-12-29.
        assertEquals(LocalDate.of(2025, 12, 29), w1.from());
        assertEquals(LocalDate.of(2026, 1, 4), w1.to());
    }

    @Test
    void containsAgreesWithWeekKeyForEveryDayOfAYear() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        while (d.getYear() == 2026) {
            assertTrue(Period.contains(Period.weekKey(d), d), "weekKey of " + d + " must contain it");
            d = d.plusDays(1);
        }
    }

    @Test
    void containsRejectsADayOutsideTheRange() {
        assertTrue(Period.contains("2026-09", LocalDate.of(2026, 9, 30)));
        assertFalse(Period.contains("2026-09", LocalDate.of(2026, 10, 1)));
        assertFalse(Period.contains("2026", LocalDate.of(2025, 12, 31)));
        assertFalse(Period.contains("2026-W01", LocalDate.of(2026, 1, 5)));
    }

    @Test
    void anUnknownPeriodIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Period.bounds("2026-13"));
        assertThrows(IllegalArgumentException.class, () -> Period.bounds("last week"));
    }
}
