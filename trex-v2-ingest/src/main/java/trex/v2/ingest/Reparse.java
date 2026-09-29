package trex.v2.ingest;

import trex.v2.core.Fact;
import trex.v2.core.Ids;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The re-parse diff (V2-PROPOSAL.md §8.2). Re-reading stored evidence with a fixed parser produces
 * candidates; this diffs them against the facts previously read from the same evidence:
 * <ul>
 *   <li>{@code MATCHED} — same id, no-op;</li>
 *   <li>{@code SHIFTED} — same row, a new id → propose {@code SUPERSEDE(from, to)};</li>
 *   <li>{@code NEW} — a row previously dropped → a new fact;</li>
 *   <li>{@code MISSING} — a row previously read but no longer → propose {@code RETIRE}.</li>
 * </ul>
 * Pure: ids are minted with the same rule the sequencer uses, so the diff preview is exact.
 */
public final class Reparse {

    public enum Kind { MATCHED, SHIFTED, NEW, MISSING }

    public record Proposal(Kind kind, String externalId, String previousId, FactDraft candidate, String detail) {}

    private Reparse() {}

    public static List<Proposal> diff(List<Fact> currentFacts, String evidenceId, List<FactDraft> candidates) {
        Map<String, Fact> previous = new TreeMap<>();
        for (Fact f : currentFacts) {
            if (evidenceId.equals(f.evidenceId())) {
                previous.put(f.externalId(), f);
            }
        }
        List<String> mintedIds = mintIds(currentFacts, candidates);
        Map<String, List<Fact>> byAmount = new LinkedHashMap<>();
        previous.values().forEach(p -> byAmount.computeIfAbsent(rowKey(p), k -> new ArrayList<>()).add(p));

        List<Proposal> proposals = new ArrayList<>();
        Set<String> matchedPrevious = new TreeSet<>();
        for (int i = 0; i < candidates.size(); i++) {
            FactDraft candidate = candidates.get(i);
            String id = mintedIds.get(i);
            Fact prev = previous.get(id);
            if (prev != null) {
                proposals.add(new Proposal(Kind.MATCHED, id, id, candidate, "unchanged"));
                matchedPrevious.add(id);
                continue;
            }
            Fact sameRow = findSameRow(byAmount.get(rowKey(candidate)), matchedPrevious, candidate);
            if (sameRow != null) {
                matchedPrevious.add(sameRow.externalId());
                proposals.add(new Proposal(Kind.SHIFTED, id, sameRow.externalId(), candidate,
                    "same row, new id: supersede " + sameRow.externalId() + " -> " + id));
            } else {
                proposals.add(new Proposal(Kind.NEW, id, null, candidate, "row previously dropped"));
            }
        }
        for (Fact p : previous.values()) {
            if (!matchedPrevious.contains(p.externalId())) {
                proposals.add(new Proposal(Kind.MISSING, p.externalId(), p.externalId(), null,
                    "row no longer read: retire " + p.externalId()));
            }
        }
        proposals.sort(Comparator.comparing((Proposal p) -> p.kind().ordinal())
            .thenComparing(Proposal::externalId));
        return proposals;
    }

    /** A previous fact for the same (account, date, amount): exact text first, then a text fix. */
    private static Fact findSameRow(List<Fact> candidates, Set<String> matched, FactDraft draft) {
        if (candidates == null) {
            return null;
        }
        for (Fact f : candidates) {
            if (!matched.contains(f.externalId()) && f.rawDescription().equals(draft.rawDescription())) {
                return f;
            }
        }
        for (Fact f : candidates) {
            if (!matched.contains(f.externalId())
                && trex.v2.core.MerchantStem.similar(f.rawDescription(), draft.rawDescription(), 0.5)) {
                return f;
            }
        }
        return null;
    }

    /** The candidate ids, minted with the sequencer's occ claim so a preview equals what would land. */
    public static List<String> mintIds(List<Fact> currentFacts, List<FactDraft> candidates) {
        Map<String, List<Fact>> byDay = new LinkedHashMap<>();
        for (Fact f : currentFacts) {
            byDay.computeIfAbsent(f.accountRef() + '\u0000' + f.date(), k -> new ArrayList<>()).add(f);
        }
        Map<String, Set<Integer>> used = new HashMap<>();
        Map<String, Set<Integer>> claimed = new HashMap<>();
        List<String> ids = new ArrayList<>();
        for (FactDraft d : candidates) {
            String receipt = d.receipt() == null || d.receipt().isBlank() ? null : d.receipt();
            int occ;
            if (receipt != null) {
                occ = 0;
            } else {
                String day = d.accountRef() + '\u0000' + d.date();
                Set<Integer> u = used.computeIfAbsent(day, k -> allOcc(byDay.get(k)));
                Set<Integer> c = claimed.computeIfAbsent(day, k -> new TreeSet<>());
                occ = -1;
                for (Fact f : byDay.getOrDefault(day, List.of())) {
                    if (!c.contains(f.occ()) && f.amount() == d.amount()
                        && f.rawDescription().equals(d.rawDescription())) {
                        c.add(f.occ());
                        u.add(f.occ());
                        occ = f.occ();
                        break;
                    }
                }
                if (occ < 0) {
                    occ = 0;
                    while (u.contains(occ)) {
                        occ++;
                    }
                    u.add(occ);
                }
            }
            ids.add(Ids.externalId(d.accountRef(), d.date(), d.amount() == null ? 0 : d.amount(),
                d.rawDescription(), receipt, occ));
        }
        return ids;
    }

    private static Set<Integer> allOcc(List<Fact> facts) {
        Set<Integer> out = new TreeSet<>();
        if (facts != null) {
            facts.forEach(f -> out.add(f.occ()));
        }
        return out;
    }

    private static String rowKey(Fact f) {
        return f.accountRef() + '|' + f.date() + '|' + f.amount();
    }

    private static String rowKey(FactDraft d) {
        return d.accountRef() + '|' + d.date() + '|' + (d.amount() == null ? 0 : d.amount());
    }
}
