package trex.ws;

import trex.core.CanonicalEvent;

/**
 * A follower's in-memory view of the journal. The watcher creates a fresh fold whenever it has to
 * rebuild from offset 0, applies complete lines in journal order, and publishes {@link #snapshot()}.
 */
public interface Fold<V> {

    void apply(CanonicalEvent line);

    /** Immutable view of everything applied so far; safe to hand to other threads. */
    V snapshot();
}
