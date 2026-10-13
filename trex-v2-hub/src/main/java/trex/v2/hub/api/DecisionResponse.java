package trex.v2.hub.api;

import trex.v2.sequencer.api.BatchResponse;
import trex.v2.sequencer.api.RowResult;

import java.util.List;

/**
 * The decision POST response (V2-INCREMENTAL-REFRESH-PLAN.md §4): the writer's {@link BatchResponse}
 * plus, when the batch was a row-scoped patch the hub applied synchronously, the rows the acting
 * client's read model now shows. The client replaces those rows by {@code externalId} instead of
 * re-fetching the view; every other batch (a fact, a structural action, a {@code REVOKE}, or a
 * patch the hub could not take without waiting) carries an empty {@code patch} and the client falls
 * back to the SSE delta or a reload. {@code postAck} returns the same shape.
 *
 * <p>Additive: the existing {@code batchHandle}/{@code batchStatus}/{@code results} are unchanged,
 * so the MCP write tools that share this path keep working and simply ignore the extra field.
 */
public record DecisionResponse(String batchHandle, String batchStatus,
                               List<RowResult> results, List<LedgerRow> patch) {

    public DecisionResponse {
        patch = patch == null ? List.of() : List.copyOf(patch);
    }

    /** Wrap the writer's batch response with the synchronous patch (empty when there is none). */
    public static DecisionResponse of(BatchResponse response, List<LedgerRow> patch) {
        return new DecisionResponse(response.batchHandle(), response.batchStatus(),
            response.results(), patch);
    }
}
