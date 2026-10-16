package trex.v2.hub;

import java.nio.file.Path;

/**
 * What the hub needs to own an index (V2-PROPOSAL.md §7.4): the journal it follows, the index file
 * it writes, the config directory it watches, the bind address, the sequencer to forward to, an
 * optional evidence store to index, and the optional job runner to proxy (V2-PROPOSAL.md §5.5).
 * Without a runner URL the Jobs view is hidden, exactly as the hub is read-only without a sequencer.
 *
 * <p>{@code authFile} is the optional credential file (V2-SPEC.md §9.5, {@code --auth} /
 * {@code TREX_AUTH_FILE}); a null value is sign-in off (A-7). It is last so the many existing
 * callers that predate sign-in keep their shorter constructors.
 */
public record HubConfig(Path journal, Path index, Path configDir, String host, int port,
                        long refreshDebounceMs, String sequencerUrl, Path evidenceDir,
                        String runnerUrl, String runnerToken, long refreshAsOfByMs, Path authFile) {

    public static final long DEFAULT_DEBOUNCE_MS = 200;

    /**
     * How often the refresher forces a full derive to advance {@code asOf} when no write has moved
     * it (V2-INCREMENTAL-REFRESH-PLAN.md §2.3, §5 slice 5). This is a full derive, so a shorter
     * cadence keeps the time-driven outputs (pending aging, the time-relative review kinds,
     * commitment faces) fresher at the cost of CPU; 60 s is the default.
     */
    public static final long DEFAULT_ASOF_REFRESH_MS = 60_000;

    /** A read-only hub with no evidence store. */
    public HubConfig(Path journal, Path index, Path configDir, String host, int port, long refreshDebounceMs) {
        this(journal, index, configDir, host, port, refreshDebounceMs, null, null, null, null,
            DEFAULT_ASOF_REFRESH_MS, null);
    }

    public HubConfig(Path journal, Path index, Path configDir, String host, int port, long refreshDebounceMs,
                     String sequencerUrl) {
        this(journal, index, configDir, host, port, refreshDebounceMs, sequencerUrl, null, null, null,
            DEFAULT_ASOF_REFRESH_MS, null);
    }

    public HubConfig(Path journal, Path index, Path configDir, String host, int port, long refreshDebounceMs,
                     String sequencerUrl, Path evidenceDir) {
        this(journal, index, configDir, host, port, refreshDebounceMs, sequencerUrl, evidenceDir, null, null,
            DEFAULT_ASOF_REFRESH_MS, null);
    }

    /**
     * The pre-sign-in full shape, with sign-in off. Kept so every caller that named all eleven
     * components (HubCommand before {@code --auth}, the tests) still compiles unchanged.
     */
    public HubConfig(Path journal, Path index, Path configDir, String host, int port, long refreshDebounceMs,
                     String sequencerUrl, Path evidenceDir, String runnerUrl, String runnerToken,
                     long refreshAsOfByMs) {
        this(journal, index, configDir, host, port, refreshDebounceMs, sequencerUrl, evidenceDir,
            runnerUrl, runnerToken, refreshAsOfByMs, null);
    }

    public HubConfig {
        if (journal == null || index == null || configDir == null) {
            throw new IllegalArgumentException("journal, index and configDir are required");
        }
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        if (refreshDebounceMs < 0) {
            throw new IllegalArgumentException("refreshDebounceMs must be >= 0");
        }
        if (refreshAsOfByMs < 0) {
            throw new IllegalArgumentException("refreshAsOfByMs must be >= 0");
        }
        if (sequencerUrl != null && !sequencerUrl.isBlank()
            && !(sequencerUrl.startsWith("http://") || sequencerUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("sequencerUrl must be an http(s) URL");
        }
        if (runnerUrl != null && !runnerUrl.isBlank()
            && !(runnerUrl.startsWith("http://") || runnerUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("runnerUrl must be an http(s) URL");
        }
    }
}
