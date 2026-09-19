package trex.journal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether the journal is there to be read yet (SPEC §5.1). A missing journal is a state, not an
 * error: a follower may be started before the sequencer has created the journal, and the file may
 * be moved aside under a running follower by {@code source != target} recovery (§3.2). Either way
 * the pass is skipped, nothing is consumed and the persisted offset is untouched.
 *
 * <p>Waiting is indefinite and costs nothing: {@link JournalChanges} watches the journal's parent
 * directory, so the follower is woken by the {@code ENTRY_CREATE} that makes it readable. The
 * absence is logged once per episode rather than once per pass, which would be one line per
 * fallback interval for as long as the sequencer is down. A {@code --once} drain shares the check
 * and simply reports nothing consumed.
 */
public final class JournalPresence {

    private static final Logger log = LoggerFactory.getLogger(JournalPresence.class);

    private final Path journal;
    private boolean waiting;

    public JournalPresence(Path journal) {
        this.journal = journal;
    }

    /** True when a pass can run; false means "no journal yet, skip this pass and wait". */
    public boolean ready() {
        if (Files.exists(journal)) {
            if (waiting) {
                log.info("journal {} appeared; following", journal);
                waiting = false;
            }
            return true;
        }
        if (!waiting) {
            log.info("journal {} does not exist yet; nothing to follow", journal);
            waiting = true;
        }
        return false;
    }
}
