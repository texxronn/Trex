package trex.v2.hub;

import java.nio.file.Path;

/**
 * What the hub needs to own an index (V2-PROPOSAL.md §7.4): the journal it follows, the index file
 * it writes, the config directory it watches, and the bind address.
 */
public record HubConfig(Path journal, Path index, Path configDir, String host, int port,
                        long refreshDebounceMs, String sequencerUrl) {

    public static final long DEFAULT_DEBOUNCE_MS = 200;

    /** Convenience for a read-only hub; mutating endpoints answer 503. */
    public HubConfig(Path journal, Path index, Path configDir, String host, int port, long refreshDebounceMs) {
        this(journal, index, configDir, host, port, refreshDebounceMs, null);
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
        if (sequencerUrl != null && !sequencerUrl.isBlank()
            && !(sequencerUrl.startsWith("http://") || sequencerUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("sequencerUrl must be an http(s) URL");
        }
    }
}
