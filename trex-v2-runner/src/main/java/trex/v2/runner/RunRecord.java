package trex.v2.runner;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One run of one job. Mutable while it executes, then a frozen history entry. Runs are operational
 * telemetry: they are never written to the journal and never to the hub's index (V2-PROPOSAL.md
 * §5.5). Output is bounded — a run is a convenience, not a record.
 */
public final class RunRecord {

    public enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    private static final int MAX_LINES = 2000;

    private final String id;
    private final String job;
    private final JsonNode params;
    private final List<Step> steps;
    private final long queuedAt = System.currentTimeMillis();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private final Map<String, Integer> stepExits = new LinkedHashMap<>();

    private long produced;
    private volatile State state = State.QUEUED;
    private volatile long startedAt;
    private volatile long finishedAt;
    private volatile int exit = -1;
    private volatile Process process;
    private volatile boolean cancelRequested;

    RunRecord(String id, String job, JsonNode params, List<Step> steps) {
        this.id = id;
        this.job = job;
        this.params = params;
        this.steps = List.copyOf(steps);
    }

    public String id() {
        return id;
    }

    public String job() {
        return job;
    }

    public JsonNode params() {
        return params;
    }

    public List<Step> steps() {
        return steps;
    }

    public State state() {
        return state;
    }

    public long queuedAt() {
        return queuedAt;
    }

    public long startedAt() {
        return startedAt;
    }

    public long finishedAt() {
        return finishedAt;
    }

    public int exit() {
        return exit;
    }

    public synchronized Map<String, Integer> stepExits() {
        return new LinkedHashMap<>(stepExits);
    }

    public synchronized void append(String line) {
        log.addLast(line == null ? "" : line);
        while (log.size() > MAX_LINES) {
            log.removeFirst();
        }
        produced++;
    }

    /** The absolute index of the next line to be appended; a stream cursor counts from zero. */
    public synchronized long producedCount() {
        return produced;
    }

    /** The absolute index of the oldest retained line (older lines have been trimmed). */
    public synchronized long firstIndex() {
        return produced - log.size();
    }

    /** Lines at or after {@code from}, skipping any that have already been trimmed. */
    public synchronized List<String> linesFrom(long from) {
        long first = produced - log.size();
        long skip = Math.max(0, from - first);
        List<String> out = new ArrayList<>();
        long i = 0;
        for (String line : log) {
            if (i++ >= skip) {
                out.add(line);
            }
        }
        return out;
    }

    /** Attach the running child so a cancel (or timeout) can terminate it. */
    void attach(Process p) {
        this.process = p;
        if (cancelRequested) {
            p.destroy();
        }
    }

    void markRunning() {
        startedAt = System.currentTimeMillis();
        state = State.RUNNING;
    }

    synchronized void stepExit(String label, int code) {
        stepExits.put(label, code);
    }

    void mark(State finalState, int exitCode) {
        this.exit = exitCode;
        this.finishedAt = System.currentTimeMillis();
        this.state = finalState;
        this.process = null;
    }

    boolean cancelRequested() {
        return cancelRequested;
    }

    /** Request termination; returns true if the run was still pending or running. */
    public boolean requestCancel() {
        cancelRequested = true;
        Process p = process;
        if (p != null) {
            p.destroy();
        }
        State s = state;
        return s == State.QUEUED || s == State.RUNNING;
    }
}
