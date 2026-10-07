package trex.v2.runner;

import java.util.List;
import java.util.function.Consumer;

/**
 * Starts a child step and streams its output. The runner's own worker calls this; tests supply a
 * fake so job orchestration is exercised without spawning a JVM.
 */
@FunctionalInterface
public interface CommandRunner {

    /**
     * Run one child, line by line, and return its exit code. {@code handle} receives the live
     * {@link Process} so a cancel or timeout can terminate it.
     */
    int run(List<String> argv, Consumer<String> line, Consumer<Process> handle) throws Exception;
}
