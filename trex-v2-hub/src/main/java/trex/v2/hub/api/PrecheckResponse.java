package trex.v2.hub.api;

import java.util.List;

/** The hub's fast 422 (V2-PROPOSAL.md §6.8): every precheck failure, named, before the sequencer sees anything. */
public record PrecheckResponse(List<Failure> failures) {

    public record Failure(int index, String message) {}
}
