package trex.v2.runner;

import java.util.List;

/**
 * One child process within a run. A single-file job has one step; a multi-file ingest is a batch of
 * steps so the UI can attribute each file's outcome, and each file is its own exit code.
 */
public record Step(String label, List<String> argv) {

    public Step {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("step label is required");
        }
        if (argv == null || argv.isEmpty()) {
            throw new IllegalArgumentException("step argv is required");
        }
    }
}
