package trex.sequencer.state;

import trex.core.state.Ledger;
import trex.sequencer.journal.Journal;

import java.util.stream.Stream;

/** Startup self-ingest: fold the whole journal into a fresh ledger. SPEC §3.2. */
public final class Fold {

    private Fold() {}

    public static Ledger fold(Journal journal) {
        Ledger ledger = new Ledger();
        try (Stream<trex.core.CanonicalEvent> lines = journal.replayFrom(0)) {
            lines.forEach(ledger::apply);
        }
        ledger.setHeadOffset(journal.headOffset());
        return ledger;
    }
}
