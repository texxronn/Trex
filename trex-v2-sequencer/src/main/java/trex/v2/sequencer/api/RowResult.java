package trex.v2.sequencer.api;

/**
 * One row's outcome (V2-PROPOSAL.md §6.5). Facts answer {@link #APPENDED}, {@link #DUPLICATE},
 * {@link #FLAGGED} or {@link #REJECTED}; decisions answer {@link #RESOLVED} or {@link #REJECTED}.
 * Only {@code DUPLICATE} and {@code REJECTED} write nothing.
 */
public record RowResult(String ref, String outcome, String externalId, Long n, String reason) {

    public static final String APPENDED = "Appended";
    public static final String DUPLICATE = "Duplicate";
    public static final String FLAGGED = "Flagged";
    public static final String REJECTED = "Rejected";
    public static final String RESOLVED = "Resolved";
}
