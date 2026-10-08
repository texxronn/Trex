package trex.v2.core.derive;

import trex.v2.core.Fact;
import trex.v2.core.Hashes;
import trex.v2.core.MerchantStem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure commitment detector and its cost model (V2-COMMITMENTS-PLAN.md §2.3, §2.4; Stage 1).
 *
 * <p>Input is the current posted facts — every account, matched transfer legs included. Grouping
 * is the existing frozen {@link MerchantStem#stem}; there is no new key class and no config
 * (§2.3.1). The one conclusion that removes a row from the domain is {@code role == NOOP} (§2.2).
 * A description that reduces to an empty stem cannot name a candidate and is skipped; that is the
 * only other filter, and it is a naming impossibility, not a semantic exclusion.
 * The detector reads nothing else off a row: category, leg, pairing and transfer id are invisible
 * to it, so a later reflow of those never moves a commitment. Detection proposes only candidates;
 * the declaring decisions and the occurrence matcher arrive in later stages and are not read
 * here.
 *
 * <p>Each candidate carries the series' cadence and coverage status, the price-step timeline and
 * the cost figures a registry renders. Everything is relative to {@code asOf} and to the accounts'
 * posted frontiers — never to a wall clock.
 *
 * <p>Deterministic: the output is ordered by {@code commitmentId} and does not depend on the order
 * of the input list.
 */
public final class Commitments {

    // ---- the measured thresholds (§2.3, §2.4) ----------------------------------------------

    /**
     * The detected cadence buckets, in days: {@code {7, 14, 30, 61, 91, 182, 365}} (§2.3.4).
     * Measured on the fixture: the median-gap histogram spikes exactly at these buckets; 234
     * merchant groups with ≥3 occurrences → 32 pass. (Session analysis, quoted in the plan
     * §2.3.4.)
     */
    private static final List<Cadence> CADENCE_BUCKETS = List.of(
        Cadence.WEEKLY, Cadence.FORTNIGHTLY, Cadence.MONTHLY, Cadence.BIMONTHLY,
        Cadence.QUARTERLY, Cadence.SEMIANNUAL, Cadence.ANNUAL);

    /**
     * Gap tolerance: {@code max(2 days, 20% of the bucket)} (provisional, §2.3.4). Measured gaps
     * are far tighter: on the fixture, Netflix's 25 monthly gaps run 28–34 days, Stan's 14 run
     * 28–33 and YouTube Premium's 8 run 28–34 — all inside the 24–36 window for a 30-day bucket;
     * the tolerance absorbs weekends and month lengths, not sloppiness.
     */
    private static final double TOLERANCE_FRACTION = 0.20;

    /** The floor of that tolerance: two days, so the weekly bucket is not held tighter. */
    private static final int TOLERANCE_MIN_DAYS = 2;

    /**
     * A series needs at least three occurrences (§2.3.4). Measured: the fixture has 234 merchant
     * groups with ≥3 occurrences against 4,698 current non-transfer facts, and only 32 survive
     * the naive cadence test. (Session analysis, quoted in the plan §2.3.4.)
     */
    private static final int MIN_OCCURRENCES = 3;

    /**
     * Regularity floor: in-tolerance gaps ÷ gaps ≥ 0.7 (§2.3.4). The session analysis used a
     * naive median-gap + ≥60% regularity test and passed 32 of the 234 groups; the plan fixed
     * 0.70 as the production floor. (Session analysis, quoted in the plan §2.3.4.)
     */
    private static final double MIN_REGULARITY = 0.70;

    /**
     * A consecutive amount change is a price step at {@code |Δ| ≥ 5%} or {@code |Δ| ≥ 50¢}
     * (§2.3.5). Measured steps on the fixture: {@code PAYPAL *NETFLIX AUS $7.99→$9.99} (2025-09,
     * +25%), {@code PAYPAL *MICROSOFT $14→$18} (+29%), {@code STAN $20→$42→$43.99},
     * {@code GOOGLE ONE $43.99→$44.99} and {@code APPLE $7.99→$5.99→$11.99}; the same-day
     * multiples {@code STAN $22+$20} and {@code APPLE $160+$1009} are one occurrence and never a
     * step. (Session analysis; accepted in plan §7.1.)
     */
    private static final double STEP_MIN_FRACTION = 0.05;

    /**
     * The absolute half of the step rule: half a dollar. The fixture's step {@code STAN
     * $42→$43.99} is +4.7% — under five percent — and is exactly why the cents rule exists.
     */
    private static final long STEP_MIN_CENTS = 50;

    /**
     * Coverage: a last occurrence more than two periods behind the accounts' posted frontier is
     * {@code ended} (§2.3.7). Measured: of the fixture's 25 expense series, 14 are active and 11
     * ended — {@code YOUTUBEPREMIUM}, {@code MICROSOFT} and {@code APPLE.COM/BILL} stopped.
     * (Session analysis; accepted in plan §2.1 and §7.7.)
     */
    private static final int ENDED_PERIODS = 2;

    /**
     * The trailing window for a variable commitment's annualised figure (§2.4): the last twelve
     * occurrences — twelve months of a monthly series, and the same window as the irregular
     * commitment's trailing 12-month total.
     */
    private static final int TRAILING_WINDOW = 12;

    /**
     * The foreign-currency price marker (§2.3.5, resolved question §10.6): the AUD charge varies
     * with FX, so the FCY amount is the price to compare. Fixture rows carry it glued to the
     * receipt text — {@code "...Receipt 155070Foreign Currency Amount: USD 22In OPENAI.COM..."}
     * and {@code "Foreign Currency Amount: USD 51.15In JERSEY CI"} — hence no word boundary and
     * the optional decimals. {@code AWS} and {@code OPENAI} are the measured carriers.
     */
    private static final Pattern FOREIGN_AMOUNT =
        Pattern.compile("Foreign Currency Amount:\\s*[A-Z]{3}\\s*(-?\\d+(?:\\.\\d+)?)");

    private static final Comparator<CurrentFact> BY_DATE_N = Comparator
        .comparing((CurrentFact c) -> c.fact().date())
        .thenComparingLong(c -> c.fact().n());

    private Commitments() {}

    /**
     * Detect the candidates in the current posted facts. {@code asOf} is an explicit input: the
     * posted frontier of an account is its newest fact at or before that instant, and the clock
     * is never consulted. A detected row is a {@link CommitmentOrigin#DETECTED} candidate with
     * its coverage status; suppression by decisions and rule coverage is Stage 4's.
     */
    public static List<Commitment> detect(List<CurrentFact> current, Instant asOf) {
        LocalDate asOfDate = asOf.atZone(ZoneOffset.UTC).toLocalDate();

        // The posted frontier of each account: the newest date it has shown at asOf. Coverage is
        // judged against this, so a closed account's silence is not read as an ending (§2.3.7).
        Map<String, LocalDate> frontier = new TreeMap<>();
        for (CurrentFact c : current) {
            if (c.role() == Role.NOOP || c.fact().date().isAfter(asOfDate)) {
                continue;
            }
            frontier.merge(c.fact().accountRef(), c.fact().date(),
                (a, b) -> a.isAfter(b) ? a : b);
        }

        // Group by the frozen stem across accounts; matched transfer legs are in scope and noop
        // is the one exclusion (§2.2, §2.3.2). TreeMap: groups are visited in key order, and the
        // candidates are sorted by id before returning.
        Map<String, List<CurrentFact>> groups = new TreeMap<>();
        for (CurrentFact c : current) {
            if (c.role() == Role.NOOP) {
                continue;
            }
            String key = MerchantStem.stem(c.fact().rawDescription());
            if (!key.isEmpty()) {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            }
        }

        List<Commitment> candidates = new ArrayList<>();
        for (Map.Entry<String, List<CurrentFact>> group : groups.entrySet()) {
            Commitment candidate = candidate(group.getKey(), group.getValue(), frontier);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparing(Commitment::commitmentId));
        return List.copyOf(candidates);
    }

    /**
     * One candidate, or null when the series is not a predictable cadence: fewer than three
     * occurrences, a median gap outside every bucket's tolerance, or regularity below the floor.
     */
    private static Commitment candidate(String key, List<CurrentFact> facts,
                                        Map<String, LocalDate> frontier) {
        List<CurrentFact> ordered = new ArrayList<>(facts);
        ordered.sort(BY_DATE_N);

        // One amount basis for the whole series: the FCY price when every row carries it, so FX
        // wobble is not read as usage; otherwise AUD. Mixing bases would invent steps (§2.3.5).
        boolean fcy = ordered.stream().allMatch(c -> foreignAmount(c.fact().rawDescription()) != null);

        TreeMap<LocalDate, Long> dayNet = new TreeMap<>();
        for (CurrentFact c : ordered) {
            dayNet.merge(c.fact().date(), effectiveAmount(c.fact(), fcy), Long::sum);
        }

        // Direction: the side that moved more money; a tie is an outflow. Only facts of the
        // commitment's sign can be occurrences (§2.2).
        long debits = 0;
        long credits = 0;
        for (long net : dayNet.values()) {
            if (net < 0) {
                debits += net;
            } else {
                credits += net;
            }
        }
        int sign = Math.abs(debits) >= credits ? -1 : 1;

        // One occurrence per day: same-day repeats collapse into a net amount (so gap 0 never
        // enters the gap series), and an opposite-sign net is a refund netted against the most
        // recent charge it can reverse (§2.3.3). A fully refunded charge leaves no occurrence.
        List<Occ> occurrences = new ArrayList<>();
        List<Occ> refunds = new ArrayList<>();
        for (Map.Entry<LocalDate, Long> day : dayNet.entrySet()) {
            long net = day.getValue();
            if (net * sign > 0) {
                occurrences.add(new Occ(day.getKey(), net));
            } else if (net * sign < 0) {
                refunds.add(new Occ(day.getKey(), Math.abs(net)));
            }
        }
        for (Occ refund : refunds) {
            long remaining = refund.amount;
            for (int i = occurrences.size() - 1; i >= 0 && remaining > 0; i--) {
                Occ charge = occurrences.get(i);
                if (charge.amount * sign <= 0 || charge.date.isAfter(refund.date)) {
                    continue;
                }
                long take = Math.min(remaining, Math.abs(charge.amount));
                charge.amount -= sign * take;
                remaining -= take;
            }
        }
        occurrences.removeIf(o -> o.amount == 0);
        if (occurrences.size() < MIN_OCCURRENCES) {
            return null;
        }

        List<Long> gaps = new ArrayList<>(occurrences.size() - 1);
        for (int i = 1; i < occurrences.size(); i++) {
            gaps.add(ChronoUnit.DAYS.between(occurrences.get(i - 1).date, occurrences.get(i).date));
        }
        double median = median(gaps);
        Cadence cadence = nearestBucket(median);
        if (cadence == null) {
            return null;
        }
        double tolerance = tolerance(cadence.days());
        long within = gaps.stream().filter(gap -> Math.abs(gap - cadence.days()) <= tolerance).count();
        double regularity = (double) within / gaps.size();
        if (regularity < MIN_REGULARITY) {
            return null;
        }

        // The cost timeline: an opening point plus one point per actual step. Variation inside a
        // run that only exceeds the tolerance when measured against the run's opening price is
        // residual usage variation, not a step, and flags the candidate variable (§2.3.6).
        List<Commitment.PriceStep> steps = new ArrayList<>();
        Occ first = occurrences.getFirst();
        steps.add(new Commitment.PriceStep(first.date, first.amount, null, null));
        long runAnchor = first.amount;
        boolean variable = false;
        for (int i = 1; i < occurrences.size(); i++) {
            Occ previous = occurrences.get(i - 1);
            Occ current = occurrences.get(i);
            if (isStep(previous.amount, current.amount)) {
                steps.add(new Commitment.PriceStep(current.date, current.amount, previous.amount,
                    changePct(previous.amount, current.amount)));
                runAnchor = current.amount;
            } else if (isStep(runAnchor, current.amount)) {
                variable = true;
            }
        }

        long costToDate = 0;
        for (Occ occurrence : occurrences) {
            costToDate += occurrence.amount;
        }
        Occ last = occurrences.getLast();
        Commitment.PriceStep lastStep = steps.size() > 1 ? steps.getLast() : null;
        long annualisedBasis = variable ? trailingMedian(occurrences) : last.amount;

        return new Commitment(
            candidateId(key),
            key,
            null,
            CommitmentOrigin.DETECTED,
            sign < 0 ? Commitment.OUT : Commitment.IN,
            cadence,
            variable ? AmountKind.VARIABLE : AmountKind.FIXED,
            CommitmentKind.OTHER,
            coverage(ordered, last, cadence, frontier),
            first.date,
            last.date,
            first.date,
            last.amount,
            lastStep == null ? null : lastStep.previousAmount(),
            lastStep == null ? null : lastStep.changePct(),
            lastStep == null ? null : lastStep.date(),
            steps,
            costToDate,
            (long) perYear(cadence) * annualisedBasis,
            occurrences.size(),
            regularity,
            variable,
            0,
            null,
            null,
            null,
            null);
    }

    /**
     * Coverage-relative status (§2.3.7): active while the last occurrence is within one cadence
     * plus tolerance of the accounts' posted frontier; ended once the frontier is more than two
     * periods past it; otherwise dormant. The frontier is the newest posted date of the accounts
     * the series appears on — never a wall clock.
     */
    private static CommitmentStatus coverage(List<CurrentFact> facts, Occ last, Cadence cadence,
                                             Map<String, LocalDate> frontier) {
        Set<String> accounts = new TreeSet<>();
        for (CurrentFact c : facts) {
            accounts.add(c.fact().accountRef());
        }
        LocalDate newest = null;
        for (String account : accounts) {
            LocalDate date = frontier.get(account);
            if (date != null && (newest == null || date.isAfter(newest))) {
                newest = date;
            }
        }
        if (newest == null) {
            return CommitmentStatus.ACTIVE;
        }
        long silent = ChronoUnit.DAYS.between(last.date, newest);
        if (silent > (long) ENDED_PERIODS * cadence.days()) {
            return CommitmentStatus.ENDED;
        }
        if (silent > cadence.days() + tolerance(cadence.days())) {
            return CommitmentStatus.DORMANT;
        }
        return CommitmentStatus.ACTIVE;
    }

    /** The nearest cadence bucket within {@code max(2 days, 20%)}; null when none fits. */
    private static Cadence nearestBucket(double gapDays) {
        Cadence best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Cadence bucket : CADENCE_BUCKETS) {
            double distance = Math.abs(gapDays - bucket.days());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = bucket;
            }
        }
        return bestDistance <= tolerance(best.days()) ? best : null;
    }

    private static double tolerance(int bucketDays) {
        return Math.max(TOLERANCE_MIN_DAYS, TOLERANCE_FRACTION * bucketDays);
    }

    /** The median gap; the mean of the two middle gaps when the count is even. */
    private static double median(List<Long> gaps) {
        List<Long> sorted = new ArrayList<>(gaps);
        sorted.sort(null);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
            ? sorted.get(middle)
            : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    /** A consecutive change is a step at {@code |Δ| ≥ 5%} or {@code |Δ| ≥ 50¢}. */
    private static boolean isStep(long previous, long current) {
        long delta = Math.abs(current) - Math.abs(previous);
        long base = Math.abs(previous);
        return Math.abs(delta) >= STEP_MIN_CENTS
            || Math.abs(delta) >= base * STEP_MIN_FRACTION;
    }

    private static double changePct(long previous, long current) {
        long base = Math.abs(previous);
        return 100.0 * (Math.abs(current) - base) / base;
    }

    /** Annualisation factors (§2.4): {@code 52/26/12/6/4/2/1} × the amount. */
    private static int perYear(Cadence cadence) {
        return switch (cadence) {
            case WEEKLY -> 52;
            case FORTNIGHTLY -> 26;
            case MONTHLY -> 12;
            case BIMONTHLY -> 6;
            case QUARTERLY -> 4;
            case SEMIANNUAL -> 2;
            case ANNUAL -> 1;
            case IRREGULAR -> 0;
        };
    }

    /** The variable commitment's basis: the median of the trailing occurrences (§2.4). */
    private static long trailingMedian(List<Occ> occurrences) {
        int from = Math.max(0, occurrences.size() - TRAILING_WINDOW);
        List<Long> amounts = new ArrayList<>(occurrences.size() - from);
        for (int i = from; i < occurrences.size(); i++) {
            amounts.add(occurrences.get(i).amount);
        }
        amounts.sort(null);
        int middle = amounts.size() / 2;
        return amounts.size() % 2 == 1
            ? amounts.get(middle)
            : Math.round((amounts.get(middle - 1) + amounts.get(middle)) / 2.0);
    }

    /**
     * The candidate id, {@code cand|<hex>} (§2.7), minted from the grouping key so the same
     * series keeps the same id across reflows while its content hash moves with the facts.
     */
    private static String candidateId(String key) {
        String digest = Hashes.sha256("cand|" + key);
        return "cand|" + digest.substring("sha256:".length(), "sha256:".length() + 16);
    }

    /** The row's detection amount: its FCY minor units when the series compares FCY, else AUD. */
    private static long effectiveAmount(Fact fact, boolean fcy) {
        if (!fcy) {
            return fact.amount();
        }
        Long cents = foreignAmount(fact.rawDescription());
        return fact.amount() < 0 ? -cents : cents;
    }

    /** The {@code Foreign Currency Amount:} figure in minor units, or null when absent. */
    private static Long foreignAmount(String rawDescription) {
        Matcher matcher = FOREIGN_AMOUNT.matcher(rawDescription);
        if (!matcher.find()) {
            return null;
        }
        try {
            return new BigDecimal(matcher.group(1)).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    /** A mutable occurrence while refunds are netted; dates stay in order. */
    private static final class Occ {
        final LocalDate date;
        long amount;

        Occ(LocalDate date, long amount) {
            this.date = date;
            this.amount = amount;
        }
    }
}
