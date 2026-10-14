package trex.v2.hub.api;

/**
 * The {@code POST /api/cash} result (V2-SPEC.md §9.4): the durable fact's {@code externalId}, its
 * line {@code n} and the writer's row {@code outcome} ({@code Appended}, {@code Duplicate} or
 * {@code Flagged}). {@code category} is the pinned category when the same-request {@code PIN}
 * landed, else null; {@code pinError} names why it did not. The fact is durable either way.
 */
public record CashResponse(String externalId, Long n, String outcome, String category, String pinError) {}
