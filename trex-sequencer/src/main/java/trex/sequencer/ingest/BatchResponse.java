package trex.sequencer.ingest;

import trex.core.BatchStatus;
import trex.core.CandidateResult;

import java.util.List;

/** Response envelope for POST /candidates and POST /decisions. SPEC §3.5. */
public record BatchResponse(String batchHandle, BatchStatus batchStatus, List<CandidateResult> results) {

    public BatchResponse {
        results = List.copyOf(results);
    }
}
