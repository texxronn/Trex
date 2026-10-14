package trex.v2.hub.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The status strip (V2-PROPOSAL.md §14): {@code n}, index lag, table counts, open review by kind,
 * and the three revision stamps. Firefly drift and reconciliation join it as those stages land.
 * {@code through} is the oldest statement frontier among the budget accounts — the date an
 * "all clear" holds to (V2-REVIEW-FIXES-PLAN.md §11); null before any statement.
 *
 * <p>{@code stale} is the statement-age nudge (V2-QOL-IMPROVEMENTS-PLAN.md §2): every account whose
 * frontier is older than its fetch cadence, oldest first. Empty when nothing to fetch.
 *
 * <p>{@code expectedDueThisWeek} is the expectations nudge (V2-SPEC.md §9.1): declared commitments
 * only, whose occurrence window is still open ({@code status = 'due'}) and due inside the current
 * ISO week. It counts occurrences, never commitments or dollars; {@code awaiting}, {@code missed},
 * {@code occurred}, {@code settled} and candidates are excluded.
 *
 * <p>{@code env} is the deployment's {@code TREX_ENV} — the same label the sequencer stamps into
 * the log envelope — so the UI header can say which instance (Dev/Prod) a screen belongs to.
 */
public record StatusResponse(long n, long offset, long journalHead, long lagBytes,
                             Map<String, Long> counts, Map<String, Long> reviewByKind,
                             String configRevision, String deriveVersion, String hashVersion,
                             LocalDate through, List<Stale> stale, int expectedDueThisWeek,
                             String env) {

    /**
     * One account past its {@code fetchEveryDays} cadence: the frontier it has reached, the age of
     * that frontier in days, and the effective cadence (so the UI can flag more than twice it).
     * {@code fetchEveryDays} is never null or 0 here — those accounts are never stale.
     */
    public record Stale(String account, LocalDate frontier, int days, int fetchEveryDays) {}
}
