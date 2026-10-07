package trex.v2.hub.api;

import java.util.List;

/**
 * The egress's one feed (V2-PROPOSAL.md §11.6): the projectable units, the {@code asOfN} they are
 * current at, and the {@code configRevision} that produced them. A no-change run is cheap because
 * the egress can ask only for what is since its resume point.
 */
public record UnitsResponse(long asOfN, String configRevision, String deriveVersion, String hashVersion,
                            List<ProjectionUnit> units) {}
