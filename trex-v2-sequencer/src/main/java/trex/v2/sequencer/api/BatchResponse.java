package trex.v2.sequencer.api;

import java.util.List;

/**
 * The shared response envelope (V2-PROPOSAL.md §6.7). A response describes an attempt; the line
 * describes what was actually recorded.
 */
public record BatchResponse(String batchHandle, String batchStatus, List<RowResult> results) {

    public static final String COMMITTED = "COMMITTED";
    public static final String PARTIAL = "PARTIAL";
    public static final String REJECTED = "REJECTED";
}
