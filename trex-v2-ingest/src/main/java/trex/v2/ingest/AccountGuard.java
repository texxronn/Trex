package trex.v2.ingest;

import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The account guard: scores a parsed statement against what each account's log already says, so a
 * file filed under the wrong account is caught before it is written (V2-ACCOUNT-GUARD-PLAN.md).
 *
 * <p>Several accounts share one CSV format, so the file cannot say whose it is. The evidence is in
 * the log: the overlap convention (§2.1) makes a new statement repeat the last rows of the previous
 * one, so rows that match an account's current facts are the usual proof. A zero balance is not
 * evidence (an empty account repeats it everywhere), so zero rows never count as overlap and a zero
 * opening never connects. Pure: no clock, no I/O, ordered collections only.
 */
public final class AccountGuard {

    /** Per-account evidence for one parsed file. */
    public record Score(String account, int overlapRows, LocalDate overlapFrom, LocalDate overlapTo,
                        boolean connects, boolean contradicts,
                        LocalDate contradictFrom, LocalDate contradictTo) {
        public boolean overlaps() {
            return overlapRows > 0;
        }

        public boolean fits() {
            return overlaps() || connects;
        }
    }

    public enum Kind { FITS, ELSEWHERE, NEW, CONTRADICTS, UNPROVEN }

    public record Verdict(Kind kind, String chosen, String elsewhere, List<String> candidates,
                          List<Score> scores) {
        public boolean refuses() {
            return kind == Kind.ELSEWHERE || kind == Kind.CONTRADICTS;
        }

        /** The ingest event's guard value: fits | new | unproven | elsewhere:&lt;ref&gt; | contradicts. */
        public String eventValue() {
            return switch (kind) {
                case FITS -> "fits";
                case NEW -> "new";
                case UNPROVEN -> "unproven";
                case ELSEWHERE -> "elsewhere:" + elsewhere;
                case CONTRADICTS -> "contradicts";
            };
        }
    }

    /** The row match key (Ruling R1): rawDescription is deliberately not part of it. */
    private record Key(LocalDate date, long amount, long balance) {}

    private AccountGuard() {}

    /**
     * Current facts grouped by account, each list in log order: posted, bank-published and not closed
     * by an effective SUPERSEDE or RETIRE. Pending and authored facts say nothing about the statement.
     */
    public static SortedMap<String, List<Fact>> current(List<Fact> facts, List<Decision> decisions) {
        Set<String> closed = Reparse.closedIds(decisions);
        SortedMap<String, List<Fact>> out = new TreeMap<>();
        for (Fact f : facts) {
            if (f.observation() == Observation.POSTED && f.provenance() == Provenance.BANK
                && !closed.contains(f.externalId())) {
                out.computeIfAbsent(f.accountRef(), k -> new ArrayList<>()).add(f);
            }
        }
        out.values().forEach(l -> l.sort(Comparator.comparingLong(f -> f.envelope().n())));
        return out;
    }

    /** One Score per account in {@code current}, sorted by account. */
    public static List<Score> score(List<FactDraft> drafts, SortedMap<String, List<Fact>> current) {
        LocalDate first = null;
        LocalDate last = null;
        for (FactDraft d : drafts) {
            if (first == null || d.date().isBefore(first)) {
                first = d.date();
            }
            if (last == null || d.date().isAfter(last)) {
                last = d.date();
            }
        }
        Long opening = opening(drafts);
        List<Score> scores = new ArrayList<>();
        for (var entry : current.entrySet()) {
            scores.add(scoreAccount(entry.getKey(), entry.getValue(), drafts, first, last, opening));
        }
        return scores;
    }

    private static Score scoreAccount(String account, List<Fact> facts, List<FactDraft> drafts,
                                      LocalDate first, LocalDate last, Long opening) {
        Set<Key> factKeys = new HashSet<>();
        for (Fact f : facts) {
            factKeys.add(new Key(f.date(), f.amount(), f.balance()));
        }

        int overlapRows = 0;
        LocalDate overlapFrom = null;
        LocalDate overlapTo = null;
        Set<LocalDate> fileDates = new TreeSet<>();
        Set<LocalDate> matchedDates = new TreeSet<>();
        for (FactDraft d : drafts) {
            fileDates.add(d.date());
            if (d.balance() == null) {
                continue; // a row without a balance can neither match nor be evidence
            }
            if (factKeys.contains(new Key(d.date(), amount(d), d.balance()))) {
                matchedDates.add(d.date());
                if (d.balance() != 0) {
                    overlapRows++;
                    overlapFrom = overlapFrom == null || d.date().isBefore(overlapFrom) ? d.date() : overlapFrom;
                    overlapTo = overlapTo == null || d.date().isAfter(overlapTo) ? d.date() : overlapTo;
                }
            }
        }

        // Contradiction: both sides have rows on a day inside the file's span, yet none agree.
        LocalDate contradictFrom = null;
        LocalDate contradictTo = null;
        Set<LocalDate> factDates = new TreeSet<>();
        facts.forEach(f -> factDates.add(f.date()));
        for (LocalDate day : fileDates) {
            if (factDates.contains(day) && !matchedDates.contains(day)) {
                contradictFrom = contradictFrom == null ? day : contradictFrom;
                contradictTo = day; // fileDates is sorted, so the last one wins
            }
        }

        return new Score(account, overlapRows, overlapFrom, overlapTo,
            connects(facts, first, opening), contradictFrom != null, contradictFrom, contradictTo);
    }

    /** The file opens exactly where the account stood on its newest day at or before the file's start. */
    private static boolean connects(List<Fact> facts, LocalDate first, Long opening) {
        if (opening == null || opening == 0 || first == null) {
            return false;
        }
        LocalDate newest = null;
        for (Fact f : facts) {
            if (!f.date().isAfter(first) && (newest == null || f.date().isAfter(newest))) {
                newest = f.date();
            }
        }
        if (newest == null) {
            return false;
        }
        for (Fact f : facts) {
            if (f.date().equals(newest) && f.balance() == opening) {
                return true;
            }
        }
        return false;
    }

    /**
     * The file's opening balance: the one {@code prev = balance - amount} that no row has as its own
     * balance (§6.7). None or several (a gap, a shuffled file) means no opening.
     */
    private static Long opening(List<FactDraft> drafts) {
        Set<Long> balances = new HashSet<>();
        Set<Long> prevs = new TreeSet<>();
        for (FactDraft d : drafts) {
            if (d.balance() != null) {
                balances.add(d.balance());
                prevs.add(d.balance() - amount(d));
            }
        }
        Long found = null;
        for (Long p : prevs) {
            if (!balances.contains(p)) {
                if (found != null) {
                    return null;
                }
                found = p;
            }
        }
        return found;
    }

    private static long amount(FactDraft d) {
        return d.amount() == null ? 0L : d.amount();
    }

    /** The verdict for the chosen account (R3: ELSEWHERE before NEW). */
    public static Verdict check(List<FactDraft> drafts, String chosen, SortedMap<String, List<Fact>> current) {
        List<Score> scores = score(drafts, current);
        Score mine = scores.stream().filter(s -> s.account().equals(chosen)).findFirst().orElse(null);
        List<String> others = scores.stream()
            .filter(s -> !s.account().equals(chosen) && s.fits())
            .map(Score::account)
            .toList();
        if (mine != null && mine.fits()) {
            return new Verdict(Kind.FITS, chosen, null, List.of(), scores);
        }
        if (others.size() == 1) {
            return new Verdict(Kind.ELSEWHERE, chosen, others.getFirst(), List.of(), scores);
        }
        if (mine == null) {
            return new Verdict(Kind.NEW, chosen, null, List.of(), scores);
        }
        if (mine.contradicts()) {
            return new Verdict(Kind.CONTRADICTS, chosen, null, List.of(), scores);
        }
        return new Verdict(Kind.UNPROVEN, chosen, null, others, scores);
    }

    /** Identify: the single account whose Score fits, else empty. */
    public static Optional<String> suggestion(List<Score> scores) {
        List<String> fitting = scores.stream().filter(Score::fits).map(Score::account).toList();
        return fitting.size() == 1 ? Optional.of(fitting.getFirst()) : Optional.empty();
    }
}
