package trex.v2.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The schedule grammar and phase (V2-PROPOSAL.md §5.5): intervals + a phase, not cron. */
class ScheduleTest {

    private static Schedule.Entry entry(Path dir, String yaml) throws Exception {
        Files.writeString(dir.resolve("schedule.yaml"), yaml);
        return Schedule.load(dir).get(0);
    }

    @Test
    void dailyPinsTheLocalTime(@TempDir Path dir) throws Exception {
        Schedule.Entry e = entry(dir, """
            jobs:
              - job: journal-snapshot
                every: 24h
                at: "02:30"
            """);
        assertEquals(Instant.parse("2026-09-30T02:30:00Z"),
            e.next(Instant.parse("2026-09-30T00:00:00Z")));
        assertEquals(Instant.parse("2026-10-01T02:30:00Z"),
            e.next(Instant.parse("2026-09-30T03:00:00Z")));
    }

    @Test
    void dailyKeepsLocalWallTimeAcrossDst(@TempDir Path dir) throws Exception {
        Schedule.Entry e = entry(dir, """
            jobs:
              - job: journal-snapshot
                every: 24h
                at: "02:30"
                zone: Australia/Sydney
            """);
        ZoneId sydney = ZoneId.of("Australia/Sydney");
        // A normal day: the local time is exactly 02:30.
        Instant normal = e.next(Instant.parse("2026-10-05T00:00:00Z"));
        assertEquals(LocalTime.of(2, 30), normal.atZone(sydney).toLocalTime());
        // The DST-gap day (Sydney springs forward at 02:00): 02:30 does not exist, so it shifts forward.
        Instant gap = e.next(Instant.parse("2026-10-03T12:00:00Z"));
        assertTrue(gap.atZone(sydney).toLocalTime().isAfter(LocalTime.of(2, 30)), gap.toString());
    }

    @Test
    void weeklyAnchorsOnAWeekday(@TempDir Path dir) throws Exception {
        Schedule.Entry e = entry(dir, """
            jobs:
              - job: prune-archive
                every: 7d
                on: Sun
                at: "03:00"
            """);
        Instant next = e.next(Instant.parse("2026-09-01T00:00:00Z"));
        assertTrue(next.atZone(java.time.ZoneOffset.UTC).getDayOfWeek() == DayOfWeek.SUNDAY, next.toString());
        assertEquals(LocalTime.of(3, 0), next.atZone(java.time.ZoneOffset.UTC).toLocalTime());
    }

    @Test
    void subDayStepsAGridFromAt(@TempDir Path dir) throws Exception {
        Schedule.Entry e = entry(dir, """
            jobs:
              - job: egress-firefly
                every: 6h
                at: "00:15"
            """);
        Instant next = e.next(Instant.parse("2026-09-30T07:00:00Z"));
        assertEquals(Instant.parse("2026-09-30T12:15:00Z"), next);
    }

    @Test
    void reparseEntryCarriesItsParams(@TempDir Path dir) throws Exception {
        // Schedulable like egress (QOL_Improvements.md §4, operator decision 2026-10-09): an entry
        // may carry a full apply request; --allow-apply is the real gate when it fires.
        String evidence = "sha256:" + "0".repeat(64);
        Schedule.Entry e = entry(dir, """
            jobs:
              - job: reparse
                every: 24h
                at: "03:00"
                params:
                  evidence: "%s"
                  sourceType: ing-csv
                  account: ing-salary
                  mode: apply
            """.formatted(evidence));
        assertEquals("reparse", e.job());
        assertEquals(evidence, e.params().path("evidence").asText());
        assertEquals("ing-csv", e.params().path("sourceType").asText());
        assertEquals("ing-salary", e.params().path("account").asText());
        assertEquals("apply", e.params().path("mode").asText());
    }

    @Test
    void rejectsTheGrammarBreakers(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> entry(dir, """
            jobs:
              - job: x
                every: 24h
            """));
        assertThrows(IllegalArgumentException.class, () -> entry(dir, """
            jobs:
              - job: x
                every: 90m
                at: "02:30"
            """));
        assertThrows(IllegalArgumentException.class, () -> entry(dir, """
            jobs:
              - job: x
                every: 24h
                on: Sun
                at: "02:30"
            """));
        assertThrows(IllegalArgumentException.class, () -> entry(dir, """
            jobs:
              - job: x
                every: 7d
                at: "03:00"
            """));
    }
}
