package trex.v2.hub;

import trex.v2.core.Fact;
import trex.v2.core.MerchantStem;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.Registry;
import trex.v2.core.config.TransferRules;
import trex.v2.core.derive.Period;
import trex.v2.core.derive.ReviewItem;
import trex.v2.hub.api.EyeballAnomaly;
import trex.v2.hub.api.EyeballDay;
import trex.v2.hub.api.EyeballResponse;
import trex.v2.hub.api.LedgerRow;
import trex.v2.hub.api.ReviewRow;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The eyeball walk (V2-PROPOSAL.md §10.3): the concrete checks a person runs at their cadence, plus
 * the day-by-day view. Every check is a query over the derived state with an explicit {@code asOf};
 * nothing time-relative is stored.
 *
 * <p>Pure over its inputs — the facts, review items, pending rows and the period's ledger rows that
 * the hub has already read — so it is testable without a database and returns the same walk for the
 * same inputs at the same {@code asOf}. The stem checks use {@link MerchantStem} rather than a
 * persisted column, because the proposal's schema keeps no stem on {@code txn_current} (§7.2).
 */
public final class Eyeball {

    // The nine checks of §10.3 point 2, named so the UI and the tests agree.
    public static final String BALANCE_CHAIN_BREAK = "BALANCE_CHAIN_BREAK";
    public static final String NEW_MERCHANT_STEM = "NEW_MERCHANT_STEM";
    public static final String AMOUNT_OUTLIER = "AMOUNT_OUTLIER";
    public static final String DUPLICATE_LOOKING = "DUPLICATE_LOOKING";
    public static final String RECURRING_MISSING = "RECURRING_MISSING";
    public static final String UNMATCHED_LEG = "UNMATCHED_LEG";
    public static final String STALE_PENDING = "STALE_PENDING";
    public static final String UNCATEGORIZED = "UNCATEGORIZED";
    public static final String ACCOUNT_SILENT = "ACCOUNT_SILENT";

    /** Below this many observations a percentile is noise, not an outlier. */
    private static final int MIN_OUTLIER_SAMPLES = 8;
    /** Below this much history an account has no "usual cadence" to be silent against. */
    private static final int MIN_SILENT_SAMPLES = 4;
    /** A recurring charge must have been seen in at least this many distinct months. */
    private static final int MIN_RECURRING_MONTHS = 3;

    private Eyeball() {}

    public static EyeballResponse walk(String period, String user, LocalDate asOf,
                                       List<Fact> facts, List<LedgerRow> periodRows,
                                       List<ReviewRow> review, List<PendingView> pending,
                                       Registry registry, TransferRules transferRules) {
        Period.Range range = Period.bounds(period);
        List<Fact> ordered = new ArrayList<>(facts);
        ordered.sort(Comparator.comparing(Fact::date).thenComparingLong(Fact::n));

        List<EyeballAnomaly> anomalies = new ArrayList<>();
        balanceChainBreaks(range, ordered, registry, anomalies);
        newMerchantStems(range, ordered, anomalies);
        amountOutliers(range, ordered, anomalies);
        duplicateLooking(range, ordered, transferRules.windowDays(), anomalies);
        recurringMissing(range, ordered, anomalies);
        unmatchedLegs(range, review, ordered, anomalies);
        stalePending(range, pending, anomalies);
        uncategorized(periodRows, anomalies);
        silentAccounts(asOf, ordered, anomalies);
        anomalies.sort(Comparator.comparing(EyeballAnomaly::kind)
            .thenComparing(a -> a.date() == null ? LocalDate.MAX : a.date())
            .thenComparing(a -> a.subject() == null ? "" : a.subject()));

        return new EyeballResponse(period, user, asOf, openItems(range, review, ordered, pending),
            anomalies, days(periodRows));
    }

    // ---- the nine checks --------------------------------------------------------------------

    /** Statement balances must chain: each row's balance is the previous row's plus its amount. */
    private static void balanceChainBreaks(Period.Range range, List<Fact> ordered, Registry registry,
                                           List<EyeballAnomaly> out) {
        Set<String> statement = new HashSet<>();
        for (Account a : registry.accounts().values()) {
            if (a.balanceSource() == BalanceSource.STATEMENT) {
                statement.add(a.ref());
            }
        }
        Map<String, List<Fact>> byAccount = new TreeMap<>();
        for (Fact f : ordered) {
            if (statement.contains(f.accountRef())) {
                byAccount.computeIfAbsent(f.accountRef(), k -> new ArrayList<>()).add(f);
            }
        }
        for (List<Fact> chain : byAccount.values()) {
            Fact prev = null;
            for (Fact f : chain) {
                if (prev != null && f.balance() != prev.balance() + f.amount() && range.contains(f.date())) {
                    out.add(new EyeballAnomaly(BALANCE_CHAIN_BREAK, f.externalId(), f.date(), f.accountRef(),
                        f.amount(), "balance " + f.balance() + " \u2260 previous " + prev.balance()
                            + " + amount " + f.amount() + " = " + (prev.balance() + f.amount())));
                }
                prev = f;
            }
        }
    }

    /** A merchant stem with no earlier observation: its first appearance is in this period. */
    private static void newMerchantStems(Period.Range range, List<Fact> ordered, List<EyeballAnomaly> out) {
        Map<String, LocalDate> firstSeen = new HashMap<>();
        for (Fact f : ordered) {
            String stem = stem(f);
            if (!stem.isEmpty()) {
                firstSeen.merge(stem, f.date(), (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        Set<String> reported = new HashSet<>();
        for (Fact f : ordered) {
            String stem = stem(f);
            if (stem.isEmpty() || !range.contains(f.date())) {
                continue;
            }
            if (f.date().equals(firstSeen.get(stem)) && reported.add(stem)) {
                out.add(new EyeballAnomaly(NEW_MERCHANT_STEM, f.externalId(), f.date(), f.accountRef(),
                    f.amount(), "'" + stem + "' first seen"));
            }
        }
    }

    /** An amount above the 99th percentile of its merchant's history. */
    private static void amountOutliers(Period.Range range, List<Fact> ordered, List<EyeballAnomaly> out) {
        Map<String, List<Long>> magnitudes = new HashMap<>();
        for (Fact f : ordered) {
            String stem = stem(f);
            if (!stem.isEmpty()) {
                magnitudes.computeIfAbsent(stem, k -> new ArrayList<>()).add(Math.abs(f.amount()));
            }
        }
        Map<String, Long> p99 = new HashMap<>();
        for (Map.Entry<String, List<Long>> e : magnitudes.entrySet()) {
            List<Long> values = e.getValue();
            if (values.size() < MIN_OUTLIER_SAMPLES) {
                continue;
            }
            values.sort(null);
            int index = (int) Math.ceil(0.99 * values.size()) - 1;
            long limit = values.get(Math.max(0, index));
            if (limit > 0) {
                p99.put(e.getKey(), limit);
            }
        }
        for (Fact f : ordered) {
            String stem = stem(f);
            Long limit = p99.get(stem);
            if (limit != null && range.contains(f.date()) && Math.abs(f.amount()) > limit) {
                out.add(new EyeballAnomaly(AMOUNT_OUTLIER, f.externalId(), f.date(), f.accountRef(),
                    f.amount(), Math.abs(f.amount()) + " is above the 99th percentile " + limit
                        + " for '" + stem + "'"));
            }
        }
    }

    /** Two rows with the same merchant and amount within the matcher's window look duplicated. */
    private static void duplicateLooking(Period.Range range, List<Fact> ordered, int windowDays,
                                         List<EyeballAnomaly> out) {
        Map<String, List<Fact>> groups = new HashMap<>();
        for (Fact f : ordered) {
            String stem = stem(f);
            if (!stem.isEmpty()) {
                groups.computeIfAbsent(stem + "|" + f.amount(), k -> new ArrayList<>()).add(f);
            }
        }
        for (List<Fact> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            group.sort(Comparator.comparing(Fact::date).thenComparingLong(Fact::n));
            for (int i = 1; i < group.size(); i++) {
                Fact prev = group.get(i - 1);
                Fact cur = group.get(i);
                long gap = ChronoUnit.DAYS.between(prev.date(), cur.date());
                if (gap <= windowDays && range.contains(cur.date())) {
                    out.add(new EyeballAnomaly(DUPLICATE_LOOKING, cur.externalId(), cur.date(), cur.accountRef(),
                        cur.amount(), "same as " + prev.externalId() + " (" + gap + "d earlier)"));
                }
            }
        }
    }

    /**
     * A charge seen in at least {@value #MIN_RECURRING_MONTHS} months, present last month, absent
     * this one. The period's start month is the cycle; a weekly walk asks whether the month's
     * subscription landed, not whether the week's did.
     */
    private static void recurringMissing(Period.Range range, List<Fact> ordered, List<EyeballAnomaly> out) {
        Map<String, Set<YearMonth>> months = new HashMap<>();
        for (Fact f : ordered) {
            String stem = stem(f);
            if (!stem.isEmpty()) {
                months.computeIfAbsent(stem, k -> new HashSet<>()).add(YearMonth.from(f.date()));
            }
        }
        YearMonth cycle = YearMonth.from(range.from());
        YearMonth previous = cycle.minusMonths(1);
        for (Map.Entry<String, Set<YearMonth>> e : months.entrySet()) {
            Set<YearMonth> seen = e.getValue();
            if (seen.size() >= MIN_RECURRING_MONTHS && seen.contains(previous) && !seen.contains(cycle)) {
                out.add(new EyeballAnomaly(RECURRING_MISSING, null, range.from(), null, null,
                    "'" + e.getKey() + "' appeared in " + seen.size() + " months but not " + cycle));
            }
        }
    }

    /** A transfer-shaped leg past the hold window, surfaced from the derived review item. */
    private static void unmatchedLegs(Period.Range range, List<ReviewRow> review, List<Fact> ordered,
                                      List<EyeballAnomaly> out) {
        Map<String, Fact> byId = byId(ordered);
        for (ReviewRow r : review) {
            Fact f = byId.get(r.subject());
            if (ReviewItem.UNMATCHED_LEG.equals(r.kind()) && f != null && range.contains(f.date())) {
                out.add(new EyeballAnomaly(UNMATCHED_LEG, f.externalId(), f.date(), f.accountRef(),
                    f.amount(), r.detail()));
            }
        }
    }

    /** A pending observation whose period has arrived without settling. */
    private static void stalePending(Period.Range range, List<PendingView> pending, List<EyeballAnomaly> out) {
        for (PendingView p : pending) {
            if ("STALE".equals(p.state()) && !p.date().isAfter(range.to())) {
                out.add(new EyeballAnomaly(STALE_PENDING, p.externalId(), p.date(), p.accountRef(),
                    p.amount(), "pending since " + p.date() + " has not settled"));
            }
        }
    }

    private static void uncategorized(List<LedgerRow> periodRows, List<EyeballAnomaly> out) {
        for (LedgerRow r : periodRows) {
            if ("UNCATEGORIZED".equals(r.category())) {
                out.add(new EyeballAnomaly(UNCATEGORIZED, r.externalId(), r.date(), r.accountRef(),
                    r.amount(), r.rawDescription()));
            }
        }
    }

    /** An account whose latest row is older than twice its usual gap (a feed gone quiet). */
    private static void silentAccounts(LocalDate asOf, List<Fact> ordered, List<EyeballAnomaly> out) {
        Map<String, Set<LocalDate>> dates = new TreeMap<>();
        for (Fact f : ordered) {
            dates.computeIfAbsent(f.accountRef(), k -> new HashSet<>()).add(f.date());
        }
        for (Map.Entry<String, Set<LocalDate>> e : dates.entrySet()) {
            List<LocalDate> days = new ArrayList<>(e.getValue());
            days.sort(null);
            if (days.size() < MIN_SILENT_SAMPLES) {
                continue;
            }
            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < days.size(); i++) {
                gaps.add(ChronoUnit.DAYS.between(days.get(i - 1), days.get(i)));
            }
            int from = Math.max(0, gaps.size() - 10);
            List<Long> recent = new ArrayList<>(gaps.subList(from, gaps.size()));
            recent.sort(null);
            long median = recent.get(recent.size() / 2);
            LocalDate last = days.get(days.size() - 1);
            if (median > 0 && ChronoUnit.DAYS.between(last, asOf) > 2 * median) {
                out.add(new EyeballAnomaly(ACCOUNT_SILENT, e.getKey(), last, e.getKey(), null,
                    "last row " + last + ", usual gap " + median + "d"));
            }
        }
    }

    // ---- open items and the day walk --------------------------------------------------------

    private static List<ReviewRow> openItems(Period.Range range, List<ReviewRow> review,
                                             List<Fact> ordered, List<PendingView> pending) {
        Map<String, LocalDate> subjectDate = new HashMap<>();
        for (Fact f : ordered) {
            subjectDate.put(f.externalId(), f.date());
        }
        for (PendingView p : pending) {
            subjectDate.put(p.externalId(), p.date());
        }
        List<ReviewRow> open = new ArrayList<>();
        for (ReviewRow r : review) {
            LocalDate date = subjectDate.get(r.subject());
            if (date != null && range.contains(date)) {
                open.add(r);
            }
        }
        return open;
    }

    private static List<EyeballDay> days(List<LedgerRow> periodRows) {
        Map<LocalDate, List<LedgerRow>> byDate = new TreeMap<>();
        for (LedgerRow r : periodRows) {
            byDate.computeIfAbsent(r.date(), k -> new ArrayList<>()).add(r);
        }
        List<EyeballDay> days = new ArrayList<>();
        for (Map.Entry<LocalDate, List<LedgerRow>> e : byDate.entrySet()) {
            List<LedgerRow> rows = e.getValue();
            rows.sort(Comparator.comparingLong(LedgerRow::n));
            long total = 0;
            Map<String, Long> closing = new LinkedHashMap<>();
            for (LedgerRow r : rows) {
                if (!"MATCHED".equals(r.leg())) {
                    total += r.amount();
                }
                closing.put(r.accountRef(), r.balance());
            }
            days.add(new EyeballDay(e.getKey(), total, closing, rows));
        }
        return days;
    }

    private static Map<String, Fact> byId(List<Fact> facts) {
        Map<String, Fact> out = new HashMap<>();
        for (Fact f : facts) {
            out.put(f.externalId(), f);
        }
        return out;
    }

    private static String stem(Fact f) {
        return MerchantStem.stem(f.rawDescription());
    }
}
