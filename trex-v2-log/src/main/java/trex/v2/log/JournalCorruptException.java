package trex.v2.log;

/** A complete ('\n'-terminated) log line that does not parse is corruption, not a torn tail. */
public class JournalCorruptException extends RuntimeException {

    public JournalCorruptException(String message, Throwable cause) {
        super(message, cause);
    }
}
