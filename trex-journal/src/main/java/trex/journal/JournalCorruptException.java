package trex.journal;

/** A '\n'-terminated journal line that does not parse: corruption, never silently skipped or truncated. */
public class JournalCorruptException extends RuntimeException {

    public JournalCorruptException(String message, Throwable cause) {
        super(message, cause);
    }
}
