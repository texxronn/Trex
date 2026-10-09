package trex.v2.runner;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * One child process within a run. A single-file job has one step; a multi-file ingest is a batch of
 * steps so the UI can attribute each file's outcome, and each file is its own exit code.
 *
 * <p>{@code after}, when present, receives the step's exit code once the child has finished — how
 * the inbox sweep files a staged statement under {@code done/} or {@code failed/}. It is runner
 * bookkeeping over the staging directory, never a write to the log or the index.
 */
public record Step(String label, List<String> argv, IntConsumer after) {

    public Step {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("step label is required");
        }
        if (argv == null || argv.isEmpty()) {
            throw new IllegalArgumentException("step argv is required");
        }
    }

    public Step(String label, List<String> argv) {
        this(label, argv, null);
    }
}
