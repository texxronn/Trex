package trex.core;

/** A candidate with its occurrence index; occ = 0 for natural-key candidates. SPEC §2.5. */
public record OccCandidate(Candidate c, int occ) {}
