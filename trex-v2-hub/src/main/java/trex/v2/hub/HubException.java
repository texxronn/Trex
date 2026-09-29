package trex.v2.hub;

/** A hub query or lifecycle failure. The index is disposable, so the cure is usually a rebuild. */
public class HubException extends RuntimeException {

    public HubException(String message, Throwable cause) {
        super(message, cause);
    }

    public HubException(String message) {
        super(message);
    }
}
