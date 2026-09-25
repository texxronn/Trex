package trex.category;

/**
 * A category with its provenance, so any consumer can answer <em>why</em> — a rule table nobody
 * can interrogate becomes folklore. SPEC §5.6.
 *
 * @param category the master category; one of the declared names, or a reserved one
 * @param origin   where it came from
 * @param rule     the rule that fired, or null for STRUCTURAL and NONE
 */
public record Categorized(String category, Origin origin, Rule rule) {

    public enum Origin {
        /** From the fold: a TRANSFER line or one of its legs. */
        STRUCTURAL,
        /** A one-off correction: a rule matching this externalId. */
        PIN,
        /** A general rule in categories.yaml. */
        RULE,
        /** Nothing matched. */
        NONE
    }

    public static Categorized structural() {
        return new Categorized(Categories.TRANSFER, Origin.STRUCTURAL, null);
    }

    public static Categorized none() {
        return new Categorized(Categories.UNCATEGORIZED, Origin.NONE, null);
    }

    /**
     * How the firing rule should read in a UI: "rule #7 (GROCERIES)", with the entry's own
     * {@code comment} appended when it has one — "rule #7 (GROCERIES) — the two big chains".
     */
    public String explain() {
        return switch (origin) {
            case STRUCTURAL -> "transfer (from the journal, not a rule)";
            case NONE -> "no rule matched";
            case PIN, RULE -> rule.where() + " (" + rule.category() + ")"
                + (comment() == null ? "" : " — " + comment());
        };
    }

    /** The firing entry's comment, or null when nothing fired or it carries none. */
    public String comment() {
        return rule == null ? null : rule.comment();
    }
}
