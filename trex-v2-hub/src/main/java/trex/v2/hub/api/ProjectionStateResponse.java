package trex.v2.hub.api;

import trex.v2.index.ProjectionRow;

import java.util.List;

/** {@code GET /api/projection}: the projection-state accelerator (V2-PROPOSAL.md §11.6). */
public record ProjectionStateResponse(List<ProjectionRow> rows) {}
