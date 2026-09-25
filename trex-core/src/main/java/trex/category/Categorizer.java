package trex.category;

import trex.core.CanonicalEvent;

import java.util.Set;

/**
 * The master category of a transaction. SPEC §5.6, invariant §0.7: a category is derived by
 * journal consumers, never stored in the journal.
 * <p>
 * This is the seam. {@link RuleCategorizer} is the only implementation in this phase; a different
 * engine would plug in here without disturbing the rules that already exist.
 */
public interface Categorizer {

    /**
     * @param line       the latest line for an externalId
     * @param transferIds ids that are structurally TRANSFER — the TRANSFER lines and their legs
     *                    (see {@link Transfers#ids}); they win over every rule
     */
    Categorized categorize(CanonicalEvent line, Set<String> transferIds);

    /** The declared categories, in file order. Reserved names are not included. */
    java.util.List<String> declared();
}
