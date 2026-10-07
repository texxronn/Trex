package trex.v2.hub.api;

import java.util.Map;

/** {@code POST /api/cursors}: commit feed resume points. */
public record CursorRequest(Map<String, String> cursors) {}
