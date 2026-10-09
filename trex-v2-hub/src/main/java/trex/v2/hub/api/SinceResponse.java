package trex.v2.hub.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The "since you last cleared" read (V2-QOL-IMPROVEMENTS-PLAN.md §5): what the log appended
 * after the browser's marker line {@code n}, plus the month's headroom, so the one summary line
 * can say "left this month X (was Y)" without a second fetch. A read over the disposable index;
 * the marker itself lives in the browser and is never stored.
 *
 * <p>{@code at} is the appended time of line {@code n} — the anchor for "Since Tue 08:40". When
 * that line is not in the log (a rebuilt journal with fewer lines, or {@code n = 0}), {@code at}
 * is null and every count is zero: the endpoint treats a destroyed marker as nothing rather than
 * an error, and the UI shows nothing. {@code rows} is the total of new facts and {@code accounts}
 * the per-account split. {@code batches} counts completed ingest batches after {@code n}.
 * {@code occurrences} turned {@code occurred} (the fact the matcher attached was appended after
 * {@code n}) or {@code missed} (the window closed after the marker's UTC date — the derive's own
 * calendar) since the marker. {@code decisions} are the decisions after {@code n} that are not
 * the acting viewer's own, per user, so your own clearing is not news. {@code headroom} is §9.2's
 * month figure; its {@code month} lets the client omit the "was" parenthetical once the month
 * rolls over.
 */
public record SinceResponse(Instant at, long rows, List<AccountCount> accounts, long batches,
                            long items, List<Occurrence> occurrences, List<UserCount> decisions,
                            ExpectedResponse.Headroom headroom) {

    /** New facts on one account, appended after the marker. */
    public record AccountCount(String account, long rows) {}

    /** One occurrence that turned {@code occurred} or {@code missed} since the marker. */
    public record Occurrence(String commitmentId, String commitmentName, String direction,
                             String status, LocalDate dueDate, LocalDate windowEnd) {}

    /** Decisions after the marker by one user; the acting viewer's own are excluded. */
    public record UserCount(String user, long count) {}
}
