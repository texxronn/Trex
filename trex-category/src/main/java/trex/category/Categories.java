package trex.category;

/** The two categories the rules file may never declare or assign. SPEC §5.6. */
public final class Categories {

    /** A TRANSFER line, or a leg of one: a fact of the fold, never an opinion of the rules. */
    public static final String TRANSFER = "TRANSFER";

    /** Nothing matched. Never a guess (SPEC §0.6); this is the worklist that drives rule writing. */
    public static final String UNCATEGORIZED = "UNCATEGORIZED";

    private Categories() {}

    public static boolean isReserved(String category) {
        return TRANSFER.equals(category) || UNCATEGORIZED.equals(category);
    }
}
