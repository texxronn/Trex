package trex.ingress;

/**
 * A row the parser did not send because the bank has not finalised it — not an error (SPEC §4).
 * Ingesting one would mint an id for a transaction that settles later under different text, and
 * the journal is append-only, so the pending line could never be removed.
 */
public record SkippedRow(String file, int line, String reason, String rawDescription) {
    @Override
    public String toString() {
        return "%s:%d %s: %s".formatted(file, line, reason, rawDescription);
    }
}
