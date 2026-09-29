package trex.v2.hub.api;

/** {@code POST /api/reflow/preview}: a candidate {@code categories.yaml} to run without saving (V2-PROPOSAL.md §9.3). */
public record ReflowRequest(String categories) {}
