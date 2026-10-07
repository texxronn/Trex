package trex.v2.runner;

import com.fasterxml.jackson.databind.JsonNode;
import trex.v2.log.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The runner's schedule (V2-PROPOSAL.md §5.5, §9.1): intervals only, with a phase. An entry is
 * {@code every} (a period) plus {@code at} (a local time-of-day) and, only for a week, {@code on}
 * (a single weekday) and {@code zone} (default UTC). No cron: one time, one weekday, no lists.
 *
 * <p>Day-sized periods are a <em>calendar</em> cadence — the local wall time is preserved across a
 * DST change — and sub-day periods are a grid anchored at {@code at}, stepped by {@code every}.
 */
public final class Schedule {

    /** The periods the runner accepts. 24h divides every sub-day period; 7d needs {@code on}. */
    private static final List<Duration> SUB_DAY = List.of(
        Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(3), Duration.ofHours(4),
        Duration.ofHours(6), Duration.ofHours(8), Duration.ofHours(12));
    private static final Duration DAILY = Duration.ofHours(24);
    private static final Duration WEEKLY = Duration.ofDays(7);

    public record Entry(String job, JsonNode params, Duration every, LocalTime at, DayOfWeek on,
                        ZoneId zone, boolean enabled) {

        /** The first occurrence strictly after {@code after}. */
        public Instant next(Instant after) {
            if (every.equals(WEEKLY)) {
                return nextWeekly(after);
            }
            if (every.equals(DAILY)) {
                return nextDaily(after);
            }
            return nextSubDay(after);
        }

        private Instant resolve(LocalDateTime local) {
            // A gap (spring-forward) shifts forward automatically; an overlap takes the earlier offset.
            return local.atZone(zone).toInstant();
        }

        private Instant nextDaily(Instant after) {
            LocalDate date = after.atZone(zone).toLocalDate();
            Instant candidate = resolve(LocalDateTime.of(date, at));
            if (!candidate.isAfter(after)) {
                candidate = resolve(LocalDateTime.of(date.plusDays(1), at));
            }
            return candidate;
        }

        private Instant nextWeekly(Instant after) {
            LocalDate date = after.atZone(zone).toLocalDate();
            int delta = Math.floorMod(on.getValue() - date.getDayOfWeek().getValue(), 7);
            Instant candidate = resolve(LocalDateTime.of(date.plusDays(delta), at));
            if (!candidate.isAfter(after)) {
                candidate = resolve(LocalDateTime.of(date.plusDays(delta).plusWeeks(1), at));
            }
            return candidate;
        }

        private Instant nextSubDay(Instant after) {
            long stepMinutes = every.toMinutes();
            LocalDate date = after.atZone(zone).toLocalDate();
            for (int day = 0; day <= 1; day++) {
                LocalDateTime base = LocalDateTime.of(date.plusDays(day), at);
                for (long m = 0; m < 24 * 60; m += stepMinutes) {
                    Instant candidate = resolve(base.plusMinutes(m));
                    if (candidate.isAfter(after)) {
                        return candidate;
                    }
                }
            }
            // Unreachable for the accepted periods (all divide 24h); a guard against a bad entry.
            return resolve(LocalDateTime.of(date.plusDays(1), at));
        }
    }

    private Schedule() {}

    // ---- loading ------------------------------------------------------------------------------

    private record Raw(String job, JsonNode params, String every, String at, String on,
                       String zone, Boolean enabled) {}
    private record File(List<Raw> jobs) {}

    /** Load {@code schedule.yaml}; a missing or empty file means no schedule (manual only). */
    public static List<Entry> load(Path configDir) {
        Path file = configDir.resolve("schedule.yaml");
        if (Files.notExists(file)) {
            return List.of();
        }
        File parsed = Yaml.read(file, File.class);
        if (parsed.jobs() == null || parsed.jobs().isEmpty()) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        for (Raw raw : parsed.jobs()) {
            out.add(entry(raw, file));
        }
        return out;
    }

    private static Entry entry(Raw raw, Path file) {
        if (raw.job() == null || raw.job().isBlank()) {
            throw refuse(file, "every schedule entry needs a job");
        }
        Duration every = parseEvery(raw.every(), file);
        if (raw.at() == null || raw.at().isBlank()) {
            throw refuse(file, "job '" + raw.job() + "' needs at: \"HH:MM\" (the phase)");
        }
        LocalTime at = parseAt(raw.at(), file);
        DayOfWeek on = raw.on() == null || raw.on().isBlank() ? null : parseDay(raw.on(), file);
        boolean weekly = every.equals(WEEKLY);
        if (weekly && on == null) {
            throw refuse(file, "a weekly job ('" + raw.job() + "') needs on: Sun..Sat");
        }
        if (!weekly && on != null) {
            throw refuse(file, "on: is only meaningful for every: 7d (job '" + raw.job() + "')");
        }
        ZoneId zone = raw.zone() == null || raw.zone().isBlank() ? ZoneId.of("UTC") : parseZone(raw.zone(), file);
        return new Entry(raw.job(), raw.params(), every, at, on, zone, raw.enabled() == null || raw.enabled());
    }

    private static Duration parseEvery(String value, Path file) {
        if (value == null || value.isBlank()) {
            throw refuse(file, "every: is required on a schedule entry");
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        Duration duration;
        try {
            char unit = v.charAt(v.length() - 1);
            long n = Long.parseLong(v.substring(0, v.length() - 1));
            duration = switch (unit) {
                case 'm' -> Duration.ofMinutes(n);
                case 'h' -> Duration.ofHours(n);
                case 'd' -> Duration.ofDays(n);
                default -> throw refuse(file, "every: must end in m, h or d: '" + value + "'");
            };
        } catch (NumberFormatException e) {
            throw refuse(file, "every: is not a duration: '" + value + "'");
        }
        if (!SUB_DAY.contains(duration) && !duration.equals(DAILY) && !duration.equals(WEEKLY)) {
            throw refuse(file, "every: must be one of 1h,2h,3h,4h,6h,8h,12h,24h,7d: '" + value + "'");
        }
        return duration;
    }

    private static LocalTime parseAt(String value, Path file) {
        try {
            return LocalTime.parse(value.trim());
        } catch (RuntimeException e) {
            throw refuse(file, "at: must be HH:MM: '" + value + "'");
        }
    }

    private static DayOfWeek parseDay(String value, Path file) {
        String v = value.trim().toUpperCase(Locale.ROOT);
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day.name().equals(v) || day.name().startsWith(v)) {
                return day;
            }
        }
        throw refuse(file, "on: must be a weekday, e.g. Sun: '" + value + "'");
    }

    private static ZoneId parseZone(String value, Path file) {
        try {
            return ZoneId.of(value.trim());
        } catch (RuntimeException e) {
            throw refuse(file, "zone: is not a known zone: '" + value + "'");
        }
    }

    private static IllegalArgumentException refuse(Path file, String message) {
        return new IllegalArgumentException(file.getFileName() + ": " + message);
    }
}
