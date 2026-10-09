package trex.v2.runner;

/**
 * A job had no work this time — an empty inbox. A manual run reports it; a scheduled run skips it
 * quietly, because an empty inbox every hour is the normal state, not a fault.
 */
public final class NothingToDo extends IllegalArgumentException {

    public NothingToDo(String message) {
        super(message);
    }
}
