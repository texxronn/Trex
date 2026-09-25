package trex.category;

import trex.core.CanonicalEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a proposed rule goes, and what it would do. SPEC §5.6, §5.7.
 * <p>
 * Rule order used to be called a judgement, and that was why a rule could only be generated for a
 * human to paste: a specific rule must usually precede a general one, and nothing could know which
 * existing rule a new pattern would collide with. It stopped being a judgement as soon as the
 * service held every line and every compiled rule. A candidate's match set is computable, and so
 * is which rule currently owns each row in it — at which point the insertion point follows:
 * <ul>
 *   <li>collides with no rule → <b>append</b>; order cannot matter</li>
 *   <li>takes rows from rule <i>k</i> → <b>insert before <i>k</i></b>, the earliest it collides with</li>
 *   <li>changes nothing → <b>refuse</b>, naming what already covers it</li>
 * </ul>
 * First-match-wins (§5.6) is untouched. What changed is that the arithmetic is done before the
 * write rather than guessed at after it.
 */
public final class Placement {

    private static final int SAMPLES = 5;

    private Placement() {}

    /**
     * What a candidate would do to the journal as it stands.
     *
     * @param matched      rows the candidate matches, transfers excluded — a structural TRANSFER
     *                     is a fact of the fold and no rule overrides it (§5.6)
     * @param total        their summed amount in cents
     * @param fromNone     how many are currently UNCATEGORIZED — the rule's actual yield
     * @param taken        rows it would take from an existing rule: category → count, by rule index
     * @param blockedByPin how many are pinned, and so would not change whatever this rule says
     * @param insertBefore the 1-based rule index to insert before, or null to append
     * @param refusal      why this must not be written, or null when it is fine
     * @param sampleIds    a few externalIds, so a preview can show what it is talking about
     */
    public record Coverage(int matched, long total, int fromNone, List<Taken> taken, int blockedByPin,
                           Integer insertBefore, String refusal, List<String> sampleIds) {

        public boolean allowed() {
            return refusal == null;
        }
    }

    /** Rows an existing rule owns today that the candidate would take. */
    public record Taken(int ruleIndex, String category, int count) {}

    /**
     * Dry-run a candidate rule against the journal, choosing where it would go.
     *
     * @param candidate compiled but not yet placed; its index is ignored
     */
    public static Coverage of(Rule candidate, Categorizer categorizer, List<CanonicalEvent> latestLines) {
        Set<String> transfers = Transfers.ids(latestLines);
        List<CanonicalEvent> matched = new ArrayList<>();
        Map<Integer, Taken> taken = new LinkedHashMap<>();
        long total = 0;
        int fromNone = 0;
        int blockedByPin = 0;
        boolean changesSomething = false;

        for (CanonicalEvent line : latestLines) {
            if (transfers.contains(line.externalId()) || !candidate.matches(line)) {
                continue;
            }
            matched.add(line);
            total += line.amount();
            Categorized now = categorizer.categorize(line, transfers);
            switch (now.origin()) {
                case NONE -> {
                    fromNone++;
                    changesSomething = true;
                }
                case PIN -> blockedByPin++;   // a pin beats every rule, so this row would not move
                case RULE -> {
                    Rule owner = now.rule();
                    taken.merge(owner.index(),
                        new Taken(owner.index(), owner.category(), 1),
                        (a, b) -> new Taken(a.ruleIndex(), a.category(), a.count() + b.count()));
                    if (!owner.category().equals(candidate.category())) {
                        changesSomething = true;
                    }
                }
                case STRUCTURAL -> { }        // unreachable: transfers were filtered above
            }
        }

        List<Taken> takenList = List.copyOf(taken.values());
        Integer insertBefore = takenList.stream()
            .filter(t -> !t.category().equals(candidate.category()))
            .mapToInt(Taken::ruleIndex)
            .min()
            .stream().boxed().findFirst().orElse(null);

        String refusal = refusalFor(matched.size(), changesSomething, takenList, candidate);
        List<String> samples = matched.stream().map(CanonicalEvent::externalId).limit(SAMPLES).toList();
        return new Coverage(matched.size(), total, fromNone, takenList, blockedByPin,
            insertBefore, refusal, samples);
    }

    /**
     * A rule that changes nothing is refused rather than written. Two shapes of nothing: it matches
     * no row at all (a typo in the pattern, usually), or every row it matches already resolves to
     * the category it would assign. Writing either adds a line to a file that is read top to bottom
     * by a person, in exchange for no behaviour.
     */
    private static String refusalFor(int matched, boolean changesSomething, List<Taken> taken, Rule candidate) {
        if (matched == 0) {
            return "matches no transaction in the journal";
        }
        if (!changesSomething) {
            String by = taken.isEmpty() ? "an existing rule"
                : taken.stream()
                    .map(t -> "rule #" + t.ruleIndex())
                    .reduce((a, b) -> a + ", " + b).orElseThrow();
            return "every row it matches is already " + candidate.category() + " via " + by;
        }
        return null;
    }
}
