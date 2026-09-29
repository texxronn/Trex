package trex.v2.hub.api;

import java.util.Map;

/** {@code GET /api/cursors}: the feed resume points (V2-PROPOSAL.md §12.2). */
public record CursorResponse(Map<String, String> cursors) {}
