package trex.v2.hub.api;

import trex.v2.index.ProjectionRow;

import java.util.List;

/**
 * {@code POST /api/projection}: record projection state as writes land, or replace it wholesale on
 * {@code --verify}. It is an accelerator; losing it costs requests, never a fact.
 */
public record ProjectionRequest(Boolean replace, List<ProjectionRow> rows) {}
