package trex.v2.index;

/** An index materialization failure. The index is disposable, so callers move it aside and rebuild. */
public class IndexException extends RuntimeException {

    public IndexException(String message, Throwable cause) {
        super(message, cause);
    }

    public IndexException(String message) {
        super(message);
    }
}
