package trex.v2.sequencer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
import trex.v2.core.Envelope;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.core.Unknown;
import trex.v2.core.config.Account;
import trex.v2.core.derive.AmountKind;
import trex.v2.core.derive.Cadence;
import trex.v2.core.derive.CommitmentKind;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.User;
import trex.v2.log.JsonlJournal;
import trex.v2.log.LogCodec;
import trex.v2.sequencer.api.StreamResponse;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §14.1: a stream lands verbatim, in n order, validated as it lands; a bad line stops it. */
class StreamTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    private static Sequencer sequencer(Path dir) {
        Registry registry = new Registry(
            Map.of("ing-savings", new Account("ing-savings", "AUD", BalanceSource.STATEMENT, 7)),
            Map.of("ron", new User("ron", "Ron", true, "weekly")));
        RuleSet rules = RuleSet.compile("t.yaml", new RuleSet.File(List.of("GROCERIES"), List.of()));
        return new Sequencer(new JsonlJournal(dir.resolve("j.jsonl")), registry, rules,
            Clock.fixed(AT, java.time.ZoneOffset.UTC), "Dev1    ", null);
    }

    private static String fact(long n, String id, String account) {
        Fact f = new Fact(new Envelope(n, Fact.KIND, Envelope.VERSION, AT.toEpochMilli(),
            "Dev1    ", "SRC00001", "        "), id, account, LocalDate.of(2026, 9, 1), -100, 900,
            "COLES 1234", null, 0, Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1");
        return LogCodec.encodeString(f);
    }

    @Test
    void aValidStreamLandsVerbatimInOrder(@TempDir Path dir) {
        try (Sequencer seq = sequencer(dir)) {
            StreamResponse r = seq.submitStream(List.of(fact(1, "a", "ing-savings"), fact(2, "b", "ing-savings")));
            assertNull(r.error());
            assertEquals(2, r.appended());
            assertEquals(2, r.headN());
        }
    }

    @Test
    void aGapIsRefusedNamingIt(@TempDir Path dir) {
        try (Sequencer seq = sequencer(dir)) {
            seq.submitStream(List.of(fact(1, "a", "ing-savings")));
            StreamResponse r = seq.submitStream(List.of(fact(3, "c", "ing-savings")));
            assertEquals(0, r.appended());
            assertEquals(2L, r.stoppedAt());
            assertNotNull(r.error());
            assertEquals(true, r.error().contains("contiguity"), r.error());
        }
    }

    @Test
    void anUnknownAccountOrKindIsRefused(@TempDir Path dir) {
        try (Sequencer seq = sequencer(dir)) {
            assertNotNull(seq.submitStream(List.of(fact(1, "a", "nope"))).error());
        }
        try (Sequencer seq = sequencer(dir)) {
            String unknown = LogCodec.encodeString(new Unknown(new Envelope(1, "trex.future",
                Envelope.VERSION, AT.toEpochMilli(), "Dev1    ", "SRC00001", "        ")));
            StreamResponse r = seq.submitStream(List.of(unknown));
            assertNotNull(r.error());
            assertEquals(true, r.error().contains("unknown kind"), r.error());
        }
    }

    @Test
    void aBadDecisionReferenceIsRefused(@TempDir Path dir) {
        try (Sequencer seq = sequencer(dir)) {
            Decision pin = new Decision.Pin(new Envelope(1, Decision.KIND, Envelope.VERSION,
                AT.toEpochMilli(), "Dev1    ", "SRC00001", "        "), List.of("ghost"), "GROCERIES",
                null, trex.v2.core.Actor.USER, "ron");
            StreamResponse r = seq.submitStream(List.of(LogCodec.encodeString(pin)));
            assertNotNull(r.error());
            assertEquals(true, r.error().contains("unknown fact"), r.error());
            assertEquals(0, r.appended());
        }
    }

    private static Envelope envelope(long n) {
        return new Envelope(n, Decision.KIND, Envelope.VERSION, AT.toEpochMilli(),
            "Dev1    ", "SRC00001", "        ");
    }

    private static String declare(long n, String commitmentId) {
        return LogCodec.encodeString(new Decision.DeclareCommitment(envelope(n), commitmentId, "Netflix",
            "out", Cadence.MONTHLY, AmountKind.FIXED, CommitmentKind.SUBSCRIPTION,
            List.of(new Decision.Match("NETFLIX", null)), 999L, null, null, null, Actor.USER, "ron"));
    }

    private static String pin(long n, String commitmentId, String factId) {
        return LogCodec.encodeString(new Decision.PinCommitment(envelope(n), commitmentId,
            List.of(factId), null, Actor.USER, "ron"));
    }

    private static String retire(long n, String commitmentId) {
        return LogCodec.encodeString(new Decision.RetireCommitment(envelope(n), commitmentId,
            LocalDate.of(2026, 8, 1), "gone", Actor.USER, "ron"));
    }

    private static String dismiss(long n, String item, List<String> subjects) {
        return LogCodec.encodeString(new Decision.Dismiss(envelope(n), item, subjects, null,
            Actor.USER, "ron"));
    }

    @Test
    void aStreamDismissSubjectFollowsTheReviewKind(@TempDir Path dir) {
        try (Sequencer seq = sequencer(dir)) {
            seq.submitStream(List.of(fact(1, "a", "ing-savings")));

            // A fact-id kind still resolves through the prefix.
            assertNull(seq.submitStream(List.of(dismiss(2, "UNMATCHED_LEG", List.of("a")))).error());
            StreamResponse ghostFact =
                seq.submitStream(List.of(dismiss(3, "UNMATCHED_LEG", List.of("ghost"))));
            assertNotNull(ghostFact.error());
            assertTrue(ghostFact.error().contains("unknown fact"), ghostFact.error());

            // BALANCE_BREAK names an account; a blank stem is the only SUSPECTED_RECURRING test.
            assertNotNull(seq.submitStream(List.of(dismiss(3, "BALANCE_BREAK", List.of("ghost")))).error());
            assertNull(seq.submitStream(List.of(dismiss(3, "BALANCE_BREAK", List.of("ing-savings")))).error());
            assertNull(seq.submitStream(List.of(dismiss(4, "SUSPECTED_RECURRING", List.of("NETFLIX")))).error());
            assertNotNull(seq.submitStream(List.of(dismiss(5, "SUSPECTED_RECURRING", List.of(" ")))).error());

            // A commitment kind needs a declaration in the prefix…
            StreamResponse undeclared =
                seq.submitStream(List.of(dismiss(5, "DORMANT_COMMITMENT", List.of("netflix"))));
            assertNotNull(undeclared.error());
            assertTrue(undeclared.error().contains("undeclared commitment"), undeclared.error());

            // …or earlier in the same stream; retirement keeps the id declared.
            StreamResponse ok = seq.submitStream(List.of(declare(5, "netflix"),
                dismiss(6, "DORMANT_COMMITMENT", List.of("netflix")),
                retire(7, "netflix"),
                dismiss(8, "COMMITMENT_ARREARS", List.of("netflix"))));
            assertNull(ok.error(), ok.error());
            assertEquals(4, ok.appended());
        }
    }

    @Test
    void aStreamCommitmentReferenceMustBeDeclaredEarlierInTheStream(@TempDir Path dir) {
        try (Sequencer seq = sequencer(dir)) {
            seq.submitStream(List.of(fact(1, "a", "ing-savings")));
            StreamResponse refused = seq.submitStream(List.of(pin(2, "netflix", "a")));
            assertNotNull(refused.error());
            assertEquals(true, refused.error().contains("undeclared commitment"), refused.error());
            assertEquals(0, refused.appended());
        }

        // A declaration earlier in the same stream counts as prefix for the lines after it; an
        // undeclared RETIRE_COMMITMENT stays allowed (structure only, §2.6).
        try (Sequencer seq = sequencer(dir)) {
            StreamResponse ok = seq.submitStream(List.of(declare(2, "netflix"), pin(3, "netflix", "a"),
                retire(4, "agl")));
            assertNull(ok.error(), ok.error());
            assertEquals(3, ok.appended());
        }
    }
}
