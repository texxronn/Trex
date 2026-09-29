package trex.v2.hub.api;

import trex.v2.core.derive.Opening;

import java.util.List;

/** {@code GET /api/opening}: where each account stood before trex saw anything (V2-PROPOSAL.md §11.3). */
public record OpeningResponse(List<Opening.PerAccount> accounts) {}
