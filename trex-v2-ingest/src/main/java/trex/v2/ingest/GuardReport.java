package trex.v2.ingest;

import trex.v2.core.Fact;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;

/**
 * The words the account guard prints (V2-ACCOUNT-GUARD-PLAN.md §2): the refusal, the notes on a
 * posted file and the {@code --identify} table. Kept apart from {@link AccountGuard} so the guard
 * stays a pure scorer and the runner and the CLI say the same thing.
 */
public final class GuardReport {

    private GuardReport() {}

    /** The lines for one verdict; the runner prints only the refusal, NEW and UNPROVEN ones. */
    public static List<String> verdict(String file, AccountGuard.Verdict v, List<FactDraft> drafts,
                                       SortedMap<String, List<Fact>> current) {
        List<String> lines = new ArrayList<>();
        switch (v.kind()) {
            case FITS -> lines.add("account guard: " + file + " fits " + v.chosen());
            case NEW -> lines.add("account guard: new account");
            case UNPROVEN -> {
                List<Fact> mine = current.get(v.chosen());
                LocalDate newest = mine.stream().map(Fact::date).max(LocalDate::compareTo).orElseThrow();
                String line = "account guard: unproven — no overlap with " + v.chosen() + " (newest row "
                    + newest + ", file starts " + first(drafts) + ")";
                if (!v.candidates().isEmpty()) {
                    line += "; the file fits " + String.join(", ", v.candidates());
                }
                lines.add(line);
            }
            case ELSEWHERE -> {
                lines.add("account guard: " + file + " does not fit " + v.chosen());
                lines.add("  fits " + v.elsewhere() + ": " + evidence(score(v, v.elsewhere()),
                    current.get(v.elsewhere()), drafts, true));
                lines.add("  re-run with --account " + v.elsewhere() + ", or --account-guard=off to post anyway");
            }
            case CONTRADICTS -> {
                AccountGuard.Score mine = score(v, v.chosen());
                lines.add("account guard: " + file + " does not fit " + v.chosen());
                lines.add("  " + v.chosen() + " already has other rows on " + mine.contradictFrom() + ".."
                    + mine.contradictTo());
                lines.add("  re-run with --account-guard=off to post anyway");
            }
        }
        return lines;
    }

    /** The {@code --identify} text: one line per account with current facts, then the suggestion. */
    public static List<String> identify(String file, List<AccountGuard.Score> scores,
                                        SortedMap<String, List<Fact>> current, List<FactDraft> drafts) {
        List<String> lines = new ArrayList<>();
        lines.add(file);
        for (AccountGuard.Score s : scores) {
            if (s.fits()) {
                lines.add(String.format("  %-16s %-12s %s", s.account(), "FITS",
                    evidence(s, current.get(s.account()), drafts, false)));
            } else if (s.contradicts()) {
                lines.add(String.format("  %-16s %-12s has other rows on %s..%s", s.account(), "CONTRADICTS",
                    s.contradictFrom(), s.contradictTo()));
            } else {
                lines.add(String.format("  %-16s no evidence", s.account()));
            }
        }
        lines.add("→ " + AccountGuard.suggestion(scores).orElse("none"));
        return lines;
    }

    /** Overlap if there is one, else the balance connection; {@code full} spells out the ingested wording. */
    private static String evidence(AccountGuard.Score s, List<Fact> facts, List<FactDraft> drafts,
                                   boolean full) {
        if (s.overlaps()) {
            return full
                ? s.overlapRows() + " rows already ingested there (" + s.overlapFrom() + ".." + s.overlapTo() + ")"
                : s.overlapRows() + " rows overlap (" + s.overlapFrom() + ".." + s.overlapTo() + ")";
        }
        return (full ? "its balance carries on from " + s.account() + "'s row of "
            : "balance carries on from ") + connectDate(facts, first(drafts));
    }

    /** The newest fact date at or before the file's start: the row the file's opening balance continues. */
    static LocalDate connectDate(List<Fact> facts, LocalDate first) {
        return facts.stream().map(Fact::date).filter(d -> !d.isAfter(first)).max(LocalDate::compareTo)
            .orElse(null);
    }

    static LocalDate first(List<FactDraft> drafts) {
        return drafts.stream().map(FactDraft::date).min(LocalDate::compareTo).orElse(null);
    }

    private static AccountGuard.Score score(AccountGuard.Verdict v, String account) {
        return v.scores().stream().filter(s -> s.account().equals(account)).findFirst().orElseThrow();
    }
}
