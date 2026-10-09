package trex.v2.core.derive;

/**
 * One fact bound to one declared commitment (V2-COMMITMENT-FACT-PLAN.md §3.1): the reverse map the
 * Hub reads to chip a ledger row and to list a commitment's transactions. Keyed by the fact,
 * because assignment is exclusive — a fact is claimed by at most one commitment — so
 * {@code externalId} is a genuine key.
 *
 * <p>{@code matchedBy} is the provenance, {@code rule} or {@code pin} (the matcher's own value). It
 * is not part of any state hash: it is a projection of the claim pass, disposable and reproduced by
 * {@code trex index --rebuild}. Unlike an occurrence, a binding is not bounded by the twelve-month
 * materialised window — a fact older than the occurrence set is still bound to its commitment.
 */
public record CommitmentFact(String externalId, String commitmentId, String matchedBy) {}
