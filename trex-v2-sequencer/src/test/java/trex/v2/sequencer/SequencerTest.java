package trex.v2.sequencer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.User;
import trex.v2.log.JsonlJournal;
import trex.v2.sequencer.api.BatchResponse;
import trex.v2.sequencer.api.DecisionBatch;
import trex.v2.sequencer.api.DecisionDraft;
import trex.v2.sequencer.api.FactBatch;
import trex.v2.sequencer.api.FactDraft;
import trex.v2.sequencer.api.RowResult;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The §6.5 dedup table, row for row, and the §6.8 line between structural validation (reject) and
 * semantic rules (record and surface).
 */
class SequencerTest {

    private static final Instant AT = Instant.parse("2026-09-29T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(AT, ZoneOffset.UTC);

    private static Registry registry() {
        Map<String, Account> accounts = Map.of(
            "ing-savings", new Account("ing-savings", "AUD", BalanceSource.STATEMENT, 7),
            "ing-orange", new Account("ing-orange", "AUD", BalanceSource.STATEMENT, 7));
        Map<String, User> users = Map.of("ron", new User("ron", "Ron", true, "weekly"));
        return new Registry(accounts, users);
    }

    private static RuleSet rules() {
        return RuleSet.compile("t.yaml", new RuleSet.File(List.of("GROCERIES", "TAXES"), List.of()));
    }

    private static Sequencer sequencer(Path dir) {
        return new Sequencer(new JsonlJournal(dir.resolve("trex.jsonl")), registry(), rules(), CLOCK);
    }

    private static FactDraft draft(String account, long amount, String raw, String receipt, long balance, String source) {
        return new FactDraft(account, LocalDate.of(2026, 9, 1), amount, balance, raw, receipt,
            Observation.POSTED, source, Provenance.BANK, null, "test/1", AT);
    }

    /** Named-setter builder for the flat decision DTO, so a test cannot miscount fields. */
    private static final class D {
        private String action;
        private String actor;
        private String user;
        private Instant at;
        private String comment;
        private String legA;
        private String legB;
        private String externalId;
        private String pendingId;
        private String postedId;
        private String item;
        private List<String> externalIds;
        private String category;
        private String fromId;
        private String toId;
        private String reason;
        private Long target;
        private String configRevision;
        private String deriveVersion;
        private String hashVersion;
        private String stateHash;
        private String text;
        private String commitmentId;
        private String name;
        private String direction;
        private String cadence;
        private String amountKind;
        private String kind;
        private List<DecisionDraft.MatchDraft> matches;
        private Long amount;
        private LocalDate anchor;
        private String fromCandidate;
        private LocalDate endedAt;
        private String candidate;
        private List<LocalDate> dueDates;

        D(String action, String actor) {
            this.action = action;
            this.actor = actor;
        }

        D user(String v) { user = v; return this; }
        D at(Instant v) { at = v; return this; }
        D comment(String v) { comment = v; return this; }
        D legs(String a, String b) { legA = a; legB = b; return this; }
        D externalId(String v) { externalId = v; return this; }
        D reason(String v) { reason = v; return this; }
        D ids(List<String> v) { externalIds = v; return this; }
        D category(String v) { category = v; return this; }
        D target(Long v) { target = v; return this; }
        D text(String v) { text = v; return this; }
        D commitmentId(String v) { commitmentId = v; return this; }
        D name(String v) { name = v; return this; }
        D direction(String v) { direction = v; return this; }
        D faces(String cadence, String amountKind, String kind) {
            this.cadence = cadence;
            this.amountKind = amountKind;
            this.kind = kind;
            return this;
        }
        D matches(List<DecisionDraft.MatchDraft> v) { matches = v; return this; }
        D amount(Long v) { amount = v; return this; }
        D anchor(LocalDate v) { anchor = v; return this; }
        D fromCandidate(String v) { fromCandidate = v; return this; }
        D endedAt(LocalDate v) { endedAt = v; return this; }
        D candidate(String v) { candidate = v; return this; }
        D dueDates(List<LocalDate> v) { dueDates = v; return this; }

        DecisionDraft build() {
            return new DecisionDraft(action, actor, user, at, comment, legA, legB, externalId, pendingId,
                postedId, item, externalIds, category, fromId, toId, reason, target,
                configRevision, deriveVersion, hashVersion, stateHash, text, null,
                commitmentId, name, direction, cadence, amountKind, kind, matches, amount, anchor,
                fromCandidate, endedAt, candidate, dueDates);
        }
    }

    private static D declare() {
        return new D("DECLARE_COMMITMENT", "user").user("ron").at(AT)
            .commitmentId("netflix").name("Netflix").direction("out")
            .faces("monthly", "fixed", "subscription")
            .matches(List.of(new DecisionDraft.MatchDraft("NETFLIX", null)));
    }

    private static String outcome(Sequencer s, D draft) {
        return s.submitDecisions(new DecisionBatch(List.of(draft.build()))).results().getFirst().outcome();
    }

    @Test
    void newIdIsAppendedThenIdenticalObservationIsDuplicate(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            FactDraft d = draft("ing-savings", -1000, "COLES 1234", null, 500, "csv");
            assertEquals(RowResult.APPENDED,
                s.submitFacts(new FactBatch(false, List.of(d))).results().getFirst().outcome());
            long head = s.headN();
            assertEquals(RowResult.DUPLICATE,
                s.submitFacts(new FactBatch(false, List.of(d))).results().getFirst().outcome());
            assertEquals(head, s.headN(), "a duplicate must append no line");
        }
    }

    @Test
    void sameContentDifferentBalanceIsFlaggedAndAppendedOnce(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            s.submitFacts(new FactBatch(false, List.of(draft("ing-savings", -1000, "COLES 1234", null, 500, "csv"))));
            assertEquals(RowResult.FLAGGED, s.submitFacts(new FactBatch(false,
                List.of(draft("ing-savings", -1000, "COLES 1234", null, 900, "csv")))).results().getFirst().outcome());
            long head = s.headN();
            assertEquals(RowResult.DUPLICATE, s.submitFacts(new FactBatch(false,
                List.of(draft("ing-savings", -1000, "COLES 1234", null, 900, "csv")))).results().getFirst().outcome());
            assertEquals(head, s.headN());
        }
    }

    @Test
    void secondSourceOfTheSameRowIsDuplicate(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            s.submitFacts(new FactBatch(false,
                List.of(draft("ing-savings", -1000, "COLES 1234", null, 500, "ing-csv"))));
            assertEquals(RowResult.DUPLICATE, s.submitFacts(new FactBatch(false,
                List.of(draft("ing-savings", -1000, "COLES 1234", null, 500, "ing-pdf"))))
                .results().getFirst().outcome(), "sourceType is excluded from the observation key");
        }
    }

    @Test
    void naturalKeyAmountChangeIsFlagged(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            s.submitFacts(new FactBatch(false,
                List.of(draft("ing-savings", -1000, "MAN market", "MAN-1", 500, "manual"))));
            assertEquals(RowResult.FLAGGED, s.submitFacts(new FactBatch(false,
                List.of(draft("ing-savings", -1200, "MAN market", "MAN-1", 500, "manual"))))
                .results().getFirst().outcome());
            assertEquals(2, s.headN(), "the new observation is kept exactly once");
        }
    }

    @Test
    void sameBatchTwoIdenticalContentRowsGetTwoOccs(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            FactDraft d = draft("ing-savings", -1000, "COLES 1234", null, 500, "csv");
            BatchResponse resp = s.submitFacts(new FactBatch(false, List.of(d, d)));
            assertEquals(RowResult.APPENDED, resp.results().get(0).outcome());
            assertEquals(RowResult.APPENDED, resp.results().get(1).outcome());
            assertEquals(2, s.headN(), "two transactions, not duplicates");
        }
    }

    @Test
    void sameBatchTwoIdenticalNaturalKeyRowsCollapse(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            FactDraft d = draft("ing-savings", -1000, "MAN market", "MAN-1", 500, "manual");
            BatchResponse resp = s.submitFacts(new FactBatch(false, List.of(d, d)));
            assertEquals(RowResult.APPENDED, resp.results().get(0).outcome());
            assertEquals(RowResult.DUPLICATE, resp.results().get(1).outcome());
            assertEquals(1, s.headN());
        }
    }

    @Test
    void allOrNoneWritesNothingWhenAnyRowIsBad(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            BatchResponse resp = s.submitFacts(new FactBatch(true, List.of(
                draft("ing-savings", -1000, "COLES 1234", null, 500, "csv"),
                draft("nope", -1000, "COLES", null, 500, "csv"))));
            assertEquals(BatchResponse.REJECTED, resp.batchStatus());
            assertEquals(0, s.headN(), "allOrNone appends nothing");
            assertTrue(resp.results().stream().allMatch(r -> r.outcome().equals(RowResult.REJECTED)));
        }
    }

    @Test
    void partialBatchAppendsTheValidRows(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            BatchResponse resp = s.submitFacts(new FactBatch(false, List.of(
                draft("ing-savings", -1000, "COLES 1234", null, 500, "csv"),
                draft("nope", -1000, "COLES", null, 500, "csv"))));
            assertEquals(BatchResponse.PARTIAL, resp.batchStatus());
            assertEquals(1, s.headN());
        }
    }

    @Test
    void decisionStructuralFaultsAreRejected(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            assertEquals(RowResult.REJECTED, s.submitDecisions(new DecisionBatch(List.of(
                new D("PAIR", "user").user("ron").at(AT).legs("ghost", "ghost2").build())))
                .results().getFirst().outcome());
            assertEquals(RowResult.REJECTED, s.submitDecisions(new DecisionBatch(List.of(
                new D("MARK_EXTERNAL", "user").user("nobody").at(AT).externalId("anything").build())))
                .results().getFirst().outcome());
            assertEquals(RowResult.REJECTED, s.submitDecisions(new DecisionBatch(List.of(
                new D("REVOKE", "user").user("ron").at(AT).target(999L).build())))
                .results().getFirst().outcome());
            assertEquals(RowResult.REJECTED, s.submitDecisions(new DecisionBatch(List.of(
                new D("PIN", "user").user("ron").at(AT).ids(List.of("x")).category("NOPE").build())))
                .results().getFirst().outcome());
        }
    }

    @Test
    void recordedFactIdsAreReferenceable(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            String id = s.submitFacts(new FactBatch(false,
                List.of(draft("ing-savings", -1000, "COLES 1234", null, 500, "csv"))))
                .results().getFirst().externalId();
            BatchResponse decision = s.submitDecisions(new DecisionBatch(List.of(
                new D("MARK_EXTERNAL", "user").user("ron").at(AT).comment("ordinary").externalId(id).build())));
            assertEquals(RowResult.RESOLVED, decision.results().getFirst().outcome());
            assertEquals(2L, decision.results().getFirst().n(), "facts and decisions share the n sequence");
        }
    }

    @Test
    void noopDecisionsNeedAKnownFactAndAReason(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            String id = s.submitFacts(new FactBatch(false,
                List.of(draft("ing-savings", -1000, "COLES 1234", null, 500, "csv"))))
                .results().getFirst().externalId();
            assertEquals(RowResult.REJECTED, s.submitDecisions(new DecisionBatch(List.of(
                new D("MARK_NOOP", "user").user("ron").at(AT).externalId(id).build())))
                .results().getFirst().outcome(), "a noop needs its reason");
            assertEquals(RowResult.REJECTED, s.submitDecisions(new DecisionBatch(List.of(
                new D("MARK_NOOP", "user").user("ron").at(AT).externalId("ghost").reason("why").build())))
                .results().getFirst().outcome(), "an unknown fact is structural");
            assertEquals(RowResult.RESOLVED, s.submitDecisions(new DecisionBatch(List.of(
                new D("MARK_NOOP", "user").user("ron").at(AT).externalId(id).reason("reference").build())))
                .results().getFirst().outcome());
            assertEquals(RowResult.RESOLVED, s.submitDecisions(new DecisionBatch(List.of(
                new D("UNMARK_NOOP", "user").user("ron").at(AT).externalId(id).comment("real").build())))
                .results().getFirst().outcome());
        }
    }

    @Test
    void semanticallyWrongButWellFormedIsRecorded(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            BatchResponse facts = s.submitFacts(new FactBatch(false, List.of(
                draft("ing-savings", -1000, "COLES 1234", null, 500, "csv"),
                draft("ing-orange", -2000, "OTHER", null, 500, "csv"))));
            String a = facts.results().get(0).externalId();
            String b = facts.results().get(1).externalId();
            assertEquals(RowResult.RESOLVED, s.submitDecisions(new DecisionBatch(List.of(
                new D("PAIR", "user").user("ron").at(AT).comment("both debits").legs(a, b).build())))
                .results().getFirst().outcome(), "a rule failure is recorded, not rejected");
        }
    }

    @Test
    void distinctContentRowsOnADayEachGetOccZeroLikeV1(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            BatchResponse resp = s.submitFacts(new FactBatch(false, List.of(
                draft("ing-savings", -1000, "COLES 1234", null, 500, "csv"),
                draft("ing-savings", -2000, "OTHER SHOP", null, 500, "csv"))));
            assertEquals(RowResult.APPENDED, resp.results().get(0).outcome());
            assertEquals(RowResult.APPENDED, resp.results().get(1).outcome());
            LocalDate day = LocalDate.of(2026, 9, 1);
            assertEquals(trex.v2.core.Ids.contentHash("ing-savings", day, -1000, "COLES 1234", 0),
                resp.results().get(0).externalId(), "distinct content rows are each occ 0, as v1");
            assertEquals(trex.v2.core.Ids.contentHash("ing-savings", day, -2000, "OTHER SHOP", 0),
                resp.results().get(1).externalId());
        }
    }

    @Test
    void migrationActorNeedsNoUser(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            String id = s.submitFacts(new FactBatch(false,
                List.of(draft("ing-savings", -1000, "COLES 1234", null, 500, "csv"))))
                .results().getFirst().externalId();
            BatchResponse resp = s.submitDecisions(new DecisionBatch(List.of(
                new D("MARK_EXTERNAL", "migrated").at(AT).externalId(id).build())));
            assertEquals(RowResult.RESOLVED, resp.results().getFirst().outcome());
        }
    }

    @Test
    void declareCommitmentValidatesItsShape(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            assertEquals(RowResult.REJECTED, outcome(s, declare().commitmentId(" ")));
            assertEquals(RowResult.REJECTED, outcome(s, declare().name(" ")));
            assertEquals(RowResult.REJECTED, outcome(s, declare().direction("sideways")));
            assertEquals(RowResult.REJECTED, outcome(s, declare().faces("daily", "fixed", "subscription")));
            assertEquals(RowResult.REJECTED,
                outcome(s, declare().faces("monthly", "estimate", "subscription")));
            assertEquals(RowResult.REJECTED, outcome(s, declare().faces("monthly", "fixed", "gadget")));
            assertEquals(RowResult.REJECTED, outcome(s, declare().matches(List.of())));
            assertEquals(RowResult.REJECTED,
                outcome(s, declare().matches(List.of(new DecisionDraft.MatchDraft(" ", null)))));
            assertEquals(RowResult.RESOLVED, outcome(s, declare().amount(999L)
                .anchor(LocalDate.of(2025, 9, 15)).fromCandidate("PAYPAL NETFLIX AUS")));
        }
    }

    @Test
    void retireCommitmentAcceptsAnUndeclaredTarget(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            // Structure only: an undeclared target is recorded, never rejected here — derivation
            // surfaces it as INEFFECTIVE_DECISION (V2-COMMITMENTS-PLAN.md §2.6, Stage 3).
            assertEquals(RowResult.RESOLVED, outcome(s, new D("RETIRE_COMMITMENT", "user").user("ron").at(AT)
                .commitmentId("ghost").endedAt(LocalDate.of(2026, 8, 1)).reason("gone")));
            assertEquals(RowResult.REJECTED, outcome(s, new D("RETIRE_COMMITMENT", "user").user("ron").at(AT)
                .commitmentId("ghost").reason("gone")));
            assertEquals(RowResult.REJECTED, outcome(s, new D("RETIRE_COMMITMENT", "user").user("ron").at(AT)
                .commitmentId(" ").endedAt(LocalDate.of(2026, 8, 1)).reason("gone")));
        }
    }

    @Test
    void ignoreRecurringNeedsCandidateAndReason(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            assertEquals(RowResult.REJECTED, outcome(s, new D("IGNORE_RECURRING", "user").user("ron").at(AT)
                .candidate("YOUTUBEPREMIUM")));
            assertEquals(RowResult.REJECTED, outcome(s, new D("IGNORE_RECURRING", "user").user("ron").at(AT)
                .reason("cancelled")));
            assertEquals(RowResult.RESOLVED, outcome(s, new D("IGNORE_RECURRING", "user").user("ron").at(AT)
                .candidate("YOUTUBEPREMIUM").reason("cancelled")));
        }
    }

    @Test
    void commitmentReferencesResolveAgainstTheFold(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            String fact = s.submitFacts(new FactBatch(false,
                List.of(draft("ing-savings", -1000, "COLES 1234", null, 500, "csv"))))
                .results().getFirst().externalId();

            // A pin to an undeclared commitment is refused, even for a known fact.
            assertEquals(RowResult.REJECTED, outcome(s, new D("PIN_COMMITMENT", "user").user("ron").at(AT)
                .commitmentId("netflix").ids(List.of(fact))));
            // A pin to an unknown fact is refused too.
            assertEquals(RowResult.REJECTED, outcome(s, new D("PIN_COMMITMENT", "user").user("ron").at(AT)
                .commitmentId("netflix").ids(List.of("ghost"))));
            // A note or a settle cannot precede the declaration either.
            assertEquals(RowResult.REJECTED, outcome(s, new D("NOTE_COMMITMENT", "user").user("ron").at(AT)
                .commitmentId("netflix").text("hello")));
            assertEquals(RowResult.REJECTED, outcome(s, new D("SETTLE_OCCURRENCE", "user").user("ron").at(AT)
                .commitmentId("netflix").dueDates(List.of(LocalDate.of(2026, 9, 1)))));

            assertEquals(RowResult.RESOLVED, outcome(s, declare()));
            assertEquals(RowResult.RESOLVED, outcome(s, new D("PIN_COMMITMENT", "user").user("ron").at(AT)
                .commitmentId("netflix").ids(List.of(fact))));
            assertEquals(RowResult.RESOLVED, outcome(s, new D("NOTE_COMMITMENT", "user").user("ron").at(AT)
                .commitmentId("netflix").text("price rise 2025-09")));
            assertEquals(RowResult.REJECTED, outcome(s, new D("NOTE_COMMITMENT", "user").user("ron").at(AT)
                .commitmentId("netflix").text(" ")));
            assertEquals(RowResult.RESOLVED, outcome(s, new D("UNPIN_COMMITMENT", "user").user("ron").at(AT)
                .ids(List.of(fact))));
        }
    }

    @Test
    void settleOccurrenceNeedsANonEmptyDateList(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            assertEquals(RowResult.RESOLVED, outcome(s, declare()));
            assertEquals(RowResult.REJECTED, outcome(s, new D("SETTLE_OCCURRENCE", "user").user("ron").at(AT)
                .commitmentId("netflix").dueDates(List.of())));
            assertEquals(RowResult.RESOLVED, outcome(s, new D("SETTLE_OCCURRENCE", "user").user("ron").at(AT)
                .commitmentId("netflix").dueDates(List.of(
                    LocalDate.of(2026, 7, 15), LocalDate.of(2026, 8, 15))).comment("paid in cash")));
        }
    }

    @Test
    void aBatchMayDeclareThenPinTheSameCommitment(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            String fact = s.submitFacts(new FactBatch(false,
                List.of(draft("ing-savings", -1000, "NETFLIX", null, 500, "csv"))))
                .results().getFirst().externalId();
            BatchResponse resp = s.submitDecisions(new DecisionBatch(List.of(
                declare().fromCandidate("PAYPAL NETFLIX AUS").build(),
                new D("PIN_COMMITMENT", "user").user("ron").at(AT)
                    .commitmentId("netflix").ids(List.of(fact)).build())));
            assertEquals(RowResult.RESOLVED, resp.results().get(0).outcome());
            assertEquals(RowResult.RESOLVED, resp.results().get(1).outcome(),
                "the confirm workflow declares and pins in one batch (§2.8)");
        }
    }

    @Test
    void decisionAllOrNoneWritesNothingWhenAnyRowIsBad(@TempDir Path dir) {
        try (Sequencer s = sequencer(dir)) {
            String id = s.submitFacts(new FactBatch(false,
                List.of(draft("ing-savings", -1000, "COLES 1234", null, 500, "csv"))))
                .results().getFirst().externalId();
            long head = s.headN();
            BatchResponse resp = s.submitDecisions(new DecisionBatch(true, List.of(
                new D("MARK_EXTERNAL", "user").user("ron").at(AT).externalId(id).build(),
                new D("REVOKE", "user").user("ron").at(AT).target(999L).build())));
            assertEquals(BatchResponse.REJECTED, resp.batchStatus());
            assertEquals(head, s.headN(), "allOrNone decisions append nothing");
        }
    }
}
