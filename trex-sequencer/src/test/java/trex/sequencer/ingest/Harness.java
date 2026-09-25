package trex.sequencer.ingest;

import trex.core.account.AccountRegistry;
import trex.core.account.Account;

import trex.core.BalanceSource;

import trex.core.Candidate;
import trex.core.CandidateResult;
import trex.core.CanonicalEvent;
import trex.core.Provenance;
import trex.sequencer.journal.JsonlJournal;
import trex.sequencer.journal.Recovery;
import trex.sequencer.state.Fold;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/** A sequencer over a temp journal, restartable like the real process. */
public final class Harness implements AutoCloseable {

    public static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

    public static final AccountRegistry REGISTRY = new AccountRegistry(List.of(
        new Account("ing-savings", "AUD", BalanceSource.STATEMENT),
        new Account("ing-orange", "AUD", BalanceSource.STATEMENT),
        new Account("cba-everyday", "AUD", BalanceSource.STATEMENT),
        new Account("bw-usd", "USD", BalanceSource.STATEMENT)));

    public static final TransferRules RULES = new TransferRules(
        List.of("Internal Transfer", "To my account", "From my account", "Fast Transfer", "Transfer from", "Osko", "PayID"), 3);

    private final Path path;
    private final UnaryOperator<String> cleaner;
    private JsonlJournal journal;
    private Sequencer sequencer;

    public Harness(Path path) {
        this(path, DescriptionCleaner::clean);
    }

    public Harness(Path path, UnaryOperator<String> cleaner) {
        this.path = path;
        this.cleaner = cleaner;
        start();
    }

    private void start() {
        Recovery.recover(path, path);
        journal = new JsonlJournal(path);
        sequencer = new Sequencer(journal, Fold.fold(journal), REGISTRY, RULES, FIXED_CLOCK, cleaner);
    }

    /** Simulate process restart: close, recover, fold. */
    public void restart() {
        journal.close();
        start();
    }

    public Sequencer sequencer() {
        return sequencer;
    }

    public BatchResponse submit(Candidate... candidates) {
        return submit(false, candidates);
    }

    public BatchResponse submit(boolean allOrNone, Candidate... candidates) {
        return sequencer.submitCandidates(allOrNone, Stream.of(candidates).map(CandidateInput::bound).toList());
    }

    public BatchResponse decide(DecisionInput... decisions) {
        return sequencer.submitDecisions(false, List.of(decisions));
    }

    public List<CanonicalEvent> lines() {
        try (Stream<CanonicalEvent> s = journal.replayFrom(0)) {
            return s.toList();
        }
    }

    public CanonicalEvent latest(String externalId) {
        return sequencer.view().latestLines().stream()
            .filter(l -> l.externalId().equals(externalId)).findFirst().orElseThrow();
    }

    public static Candidate c(String ref, String account, String date, long amount, String raw, long balance, String receipt) {
        return new Candidate(ref, account, LocalDate.parse(date), amount, raw, balance, receipt, null, null,
            "test", Provenance.BANK);
    }

    public static String id(CandidateResult r) {
        return switch (r) {
            case CandidateResult.Resolved x -> x.externalId();
            case CandidateResult.DroppedDuplicate x -> x.externalId();
            case CandidateResult.Held x -> x.externalId();
            case CandidateResult.Flagged x -> x.externalId();
            case CandidateResult.Rejected x -> throw new AssertionError("rejected: " + x.reason());
        };
    }

    @Override
    public void close() {
        journal.close();
    }
}
