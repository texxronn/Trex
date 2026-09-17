package trex.sequencer.ingest;

/** One bound POST /decisions element, or a binding error. SPEC §3.5. */
public record DecisionInput(String decisionRef, Action action, String externalId, String legA, String legB,
                            String comment, String error) {

    public enum Action { MARK_EXTERNAL, CONFIRM_TRANSFER, DISMISS_DUP }

    public static DecisionInput markExternal(String ref, String externalId, String comment) {
        return new DecisionInput(ref, Action.MARK_EXTERNAL, externalId, null, null, comment, null);
    }

    public static DecisionInput confirmTransfer(String ref, String legA, String legB, String comment) {
        return new DecisionInput(ref, Action.CONFIRM_TRANSFER, null, legA, legB, comment, null);
    }

    public static DecisionInput dismissDup(String ref, String externalId, String comment) {
        return new DecisionInput(ref, Action.DISMISS_DUP, externalId, null, null, comment, null);
    }

    public static DecisionInput unbindable(String ref, String error) {
        return new DecisionInput(ref, null, null, null, null, null, error);
    }
}
