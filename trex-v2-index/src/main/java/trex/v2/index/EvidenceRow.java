package trex.v2.index;

/**
 * One evidence-store entry (V2-PROPOSAL.md §7.2). Disposable: the store's files are the truth, and
 * this table is a queryable index over them.
 */
public record EvidenceRow(String sha256, String path, long bytes, String mediaType, String sourceType,
                          String firstSeen) {}
