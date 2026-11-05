package trex.v2.hub;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure "is a timer derive due?" rule (V2-ASOF-CADENCE-PLAN.md R-2, R-7); instants are explicit. */
class AsOfCadenceTest {

    private static Instant t(String s) {
        return Instant.parse(s);
    }

    @Test
    void dayIsDueOnlyAcrossUtcMidnight() {
        AsOfCadence day = AsOfCadence.DAY;
        assertFalse(day.due(t("2026-11-05T00:00:00Z"), t("2026-11-05T23:59:59.999Z")));
        assertTrue(day.due(t("2026-11-05T23:59:59Z"), t("2026-11-06T00:00:00Z")));
        assertTrue(day.due(t("2026-11-05T12:00:00Z"), t("2026-11-07T01:00:00Z")));
    }

    @Test
    void hourIsUtcAligned() {
        AsOfCadence hour = AsOfCadence.parse("1h");
        assertFalse(hour.due(t("2026-11-05T10:00:00Z"), t("2026-11-05T10:59:59Z")));
        assertTrue(hour.due(t("2026-11-05T10:59:59Z"), t("2026-11-05T11:00:00Z")));
        // Aligned, not elapsed: a derive at :59 is already owed again two seconds later.
        assertTrue(hour.due(t("2026-11-05T10:59:59Z"), t("2026-11-05T11:00:01Z")));
    }

    @Test
    void fifteenMinuteBoundary() {
        AsOfCadence q = AsOfCadence.parse("15m");
        assertFalse(q.due(t("2026-11-05T10:15:00Z"), t("2026-11-05T10:29:59Z")));
        assertTrue(q.due(t("2026-11-05T10:29:59Z"), t("2026-11-05T10:30:00Z")));
    }

    @Test
    void offIsNeverDue() {
        assertFalse(AsOfCadence.OFF.due(t("2020-01-01T00:00:00Z"), t("2030-01-01T00:00:00Z")));
        assertFalse(AsOfCadence.everyMs(0).due(t("2020-01-01T00:00:00Z"), t("2030-01-01T00:00:00Z")));
    }

    @Test
    void parseAcceptsCanonicalFormsCaseInsensitiveAndTrimmed() {
        assertEquals(AsOfCadence.DAY, AsOfCadence.parse(null));
        assertEquals(AsOfCadence.DAY, AsOfCadence.parse("  "));
        assertEquals(AsOfCadence.DAY, AsOfCadence.parse(" DAY "));
        assertEquals(AsOfCadence.OFF, AsOfCadence.parse("Off"));
        assertEquals("15m", AsOfCadence.parse(" 15M ").toString());
        assertEquals("1h", AsOfCadence.parse("1H").toString());
        assertEquals(3_600_000L, AsOfCadence.parse("1h").intervalMs());
    }

    @Test
    void parseRejectsBadValues() {
        for (String bad : new String[] {"0m", "-1h", "1d", "abc", "60s", "0h", "m", "99999999999999999999h"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AsOfCadence.parse(bad), bad);
            assertEquals("asOf refresh must be day, off, <n>m or <n>h (got '" + bad + "')", e.getMessage());
        }
    }
}
