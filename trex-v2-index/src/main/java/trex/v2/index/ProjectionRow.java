package trex.v2.index;

/**
 * One projection-state row (V2-PROPOSAL.md §11.6). It is an accelerator, not a record: every column
 * is recoverable from Firefly, so the table is rebuilt on {@code --verify}, never backed up.
 */
public record ProjectionRow(String unitId, String unitKind, String groupId, String category,
                            String stateHash, String configRevision, String deriveVersion,
                            String verifiedAt) {}
