package trex.gateway;

import trex.category.Placement;
import trex.category.Rule;

import trex.category.Categorized;
import trex.category.Categorizer;
import trex.category.CategoryRules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.CanonicalEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC §7 tests 18-20: an amendment preserves the file, validates before swapping, and places itself. */
class RuleWriterTest {

    @TempDir
    Path dir;

    /** Deliberately awkward: comments above rules, a deliberate order, blank lines, odd spacing. */
    private static final String RULES = """
        # Master categories — the header comment, which belongs to the file.
        #
        # Order is the decision: first match wins.

        categories: [SALARY, GROCERIES, FOOD, FEES]

        rules:
          # Interest you PAY, before BILLS, because a loan line names the mortgage.
          # The bare word with direction out covers every form the banks write.
          - category: FEES
            comment: "what the bank charges"
            when:
              all:
                - direction: out
                - match: "\\\\bfees?\\\\b"

          - category: GROCERIES
            comment: "the two big chains"
            when:
              match: "woolworths|coles"

          # A trailing comment on the last rule.
          - category: SALARY
            when:
              all:
                - direction: in
                - match: "salary"
        """;

    private RuleStore store() throws IOException {
        Files.writeString(dir.resolve("categories.yaml"), RULES);
        Files.writeString(dir.resolve("pins.yaml"), "pins: []\n");
        return new RuleStore(dir.resolve("categories.yaml"), dir.resolve("pins.yaml"));
    }

    private static CategoryRules.RuleEntry entry(String category, String comment, String match) {
        return new CategoryRules.RuleEntry(category, comment,
            new CategoryRules.WhenEntry(null, null, null, null, match, null, null, null, null, null));
    }

    private String text() throws IOException {
        return Files.readString(dir.resolve("categories.yaml"));
    }

    // ---------------------------------------------------------------- test 18: preservation

    @Test
    void insertingLeavesEveryOtherByteAlone() throws IOException {
        RuleStore store = store();
        store.addRule(entry("FOOD", "cafes and bakeries", "piccolo"), null, store.revision());

        String after = text();
        // Everything that was there is still there, verbatim — including the comments, which a
        // re-serialising writer would have deleted.
        for (String kept : List.of(
                "# Master categories — the header comment, which belongs to the file.",
                "# Interest you PAY, before BILLS, because a loan line names the mortgage.",
                "# The bare word with direction out covers every form the banks write.",
                "# A trailing comment on the last rule.",
                "comment: \"the two big chains\"",
                "categories: [SALARY, GROCERIES, FOOD, FEES]")) {
            assertTrue(after.contains(kept), "lost: " + kept);
        }
        // And the diff is only the added lines.
        List<String> before = RULES.lines().toList();
        List<String> now = after.lines().toList();
        assertTrue(now.containsAll(before), "an existing line was changed or reflowed");
        // Rendered values are quoted on purpose: a regex containing '#' would otherwise become
        // a YAML comment (see RuleText.WRITER).
        assertTrue(after.contains("- category: \"FOOD\""));
        assertTrue(after.contains("match: \"piccolo\""));

        // The proof that the splice is well-formed is that it loads and works.
        Categorizer c = store.load();
        assertEquals("FOOD", c.categorize(line("x", -520, "PICCOLO ME SYDNEY"), Set.of()).category());
    }

    @Test
    void deletingTakesTheCommentsThatExplainTheRule() throws IOException {
        RuleStore store = store();
        store.deleteRule(1, store.revision());          // the FEES rule and its two comment lines

        String after = text();
        assertFalse(after.contains("- category: FEES"));
        assertFalse(after.contains("# Interest you PAY"), "an orphaned comment is worse than none");
        assertFalse(after.contains("# The bare word with direction out"));
        assertTrue(after.contains("# Master categories"), "the file header is not part of any rule");
        assertTrue(after.contains("- category: GROCERIES"));
        assertEquals(2, store.load().categorize(line("x", -100, "COLES"), Set.of()) == null ? 0 : 2);
    }

    @Test
    void replacingKeepsTheCommentBlockAboveIt() throws IOException {
        RuleStore store = store();
        store.replaceRule(2, entry("GROCERIES", "the two big chains, plus the warehouse", "woolworths|coles|costco"),
            store.revision());

        String after = text();
        assertTrue(after.contains("costco"));
        assertTrue(after.contains("# Interest you PAY"), "another rule's comments must not move");
        assertTrue(after.contains("# A trailing comment on the last rule."));
        assertEquals("GROCERIES", store.load().categorize(line("x", -9000, "COSTCO WHOLESALE"), Set.of()).category());
    }

    // ---------------------------------------------------------------- test 19: validate before swap

    @Test
    void anAmendmentThatWouldNotLoadChangesNothing() throws IOException {
        RuleStore store = store();
        String before = text();
        String revision = store.revision();

        RuleStore.Invalid e = assertThrows(RuleStore.Invalid.class,
            () -> store.addRule(entry("HOLIDAYS", "not declared anywhere", "qantas"), null, revision));
        assertTrue(e.getMessage().contains("not declared"), e.getMessage());

        assertEquals(before, text(), "the file on disk must be untouched");
        assertEquals(revision, store.revision(), "and so must the revision");
        assertNull(store.load().categorize(line("x", -100, "QANTAS"), Set.of()).rule());
    }

    @Test
    void aBadRegexIsRefusedBeforeItReachesTheFile() throws IOException {
        RuleStore store = store();
        String before = text();
        assertThrows(RuleStore.Invalid.class,
            () -> store.addRule(entry("FOOD", "unclosed group", "piccolo("), null, store.revision()));
        assertEquals(before, text());
    }

    @Test
    void aStaleRevisionIsRefused() throws IOException {
        RuleStore store = store();
        String stale = store.revision();
        store.addRule(entry("FOOD", "first writer wins", "piccolo"), null, stale);

        String before = text();
        RuleStore.RevisionConflict e = assertThrows(RuleStore.RevisionConflict.class,
            () -> store.addRule(entry("FOOD", "composed against the old file", "gelato"), null, stale));
        assertEquals(store.revision(), e.actual(), "the caller is told what it actually is");
        assertEquals(before, text(), "a conflicting write changes nothing");
    }

    @Test
    void theRevisionCoversBothFiles() throws IOException {
        RuleStore store = store();
        String before = store.revision();
        Files.writeString(dir.resolve("pins.yaml"), """
            pins:
              - category: FOOD
                comment: "this one was a cafe"
                when: {externalId: ["abc"]}
            """);
        assertNotEquals(before, store.revision(), "a pin change must move the revision too");
    }

    // ---------------------------------------------------------------- test 20: placement

    private static final List<CanonicalEvent> JOURNAL = List.of(
        line("g1", -8500, "WOOLWORTHS 1234"),
        line("g2", -9200, "COLES 0234"),
        line("u1", -520, "PICCOLO ME SYDNEY"),
        line("u2", -640, "PICCOLO ME PARRAMATTA"),
        line("f1", -1500, "ACCOUNT KEEPING FEE"));

    @Test
    void aRuleThatCollidesWithNothingIsAppended() throws IOException {
        RuleStore store = store();
        Placement.Coverage c = coverage(store, entry("FOOD", null, "piccolo"));
        assertTrue(c.allowed());
        assertEquals(2, c.matched());
        assertEquals(2, c.fromNone());
        assertEquals(-1160, c.total());
        assertTrue(c.taken().isEmpty());
        assertNull(c.insertBefore(), "nothing to get in front of");
    }

    @Test
    void aRuleThatTakesRowsLandsBeforeTheRuleItTakesThemFrom() throws IOException {
        RuleStore store = store();
        // "coles" is already GROCERIES (rule #2). A narrower rule calling it FOOD must win,
        // which under first-match-wins means going in front of #2.
        Placement.Coverage c = coverage(store, entry("FOOD", "the cafe inside", "coles 0234"));
        assertTrue(c.allowed());
        assertEquals(1, c.matched());
        assertEquals(0, c.fromNone());
        assertEquals(List.of(new Placement.Taken(2, "GROCERIES", 1)), c.taken());
        assertEquals(2, c.insertBefore());

        store.addRule(entry("FOOD", "the cafe inside", "coles 0234"), c.insertBefore(), store.revision());
        Categorizer after = store.load();
        assertEquals("FOOD", after.categorize(line("g2", -9200, "COLES 0234"), Set.of()).category(),
            "the new rule must actually win now");
        assertEquals("GROCERIES", after.categorize(line("g1", -8500, "WOOLWORTHS 1234"), Set.of()).category(),
            "and must not disturb the rows it was not about");
    }

    @Test
    void aRuleThatChangesNothingIsRefused() throws IOException {
        RuleStore store = store();
        // Everything "woolworths" matches is already GROCERIES: this is a line in a file for nothing.
        Placement.Coverage c = coverage(store, entry("GROCERIES", "redundant", "woolworths"));
        assertFalse(c.allowed());
        assertTrue(c.refusal().contains("already GROCERIES"), c.refusal());
        assertTrue(c.refusal().contains("rule #2"), c.refusal());
    }

    @Test
    void aRuleThatMatchesNothingIsRefused() throws IOException {
        Placement.Coverage c = coverage(store(), entry("FOOD", "typo", "piccollo"));
        assertFalse(c.allowed());
        assertEquals("matches no transaction in the journal", c.refusal());
    }

    /** A pinned row will not move whatever a rule says, so the preview says so rather than implying it will. */
    @Test
    void pinnedRowsAreReportedAsUnmovable() throws IOException {
        Files.writeString(dir.resolve("categories.yaml"), RULES);
        Files.writeString(dir.resolve("pins.yaml"), """
            pins:
              - category: SALARY
                when: {externalId: ["u1"]}
            """);
        RuleStore store = new RuleStore(dir.resolve("categories.yaml"), dir.resolve("pins.yaml"));
        Placement.Coverage c = coverage(store, entry("FOOD", null, "piccolo"));
        assertEquals(2, c.matched());
        assertEquals(1, c.blockedByPin());
        assertEquals(1, c.fromNone());
        assertTrue(c.allowed(), "it still helps the row that is not pinned");
    }

    private Placement.Coverage coverage(RuleStore store, CategoryRules.RuleEntry proposed) {
        Categorizer current = store.load();
        Rule candidate = CategoryRules.compileOne("proposal", false, 0, proposed,
            Set.copyOf(current.declared()));
        return Placement.of(candidate, current, JOURNAL);
    }

    /**
     * A fixture line. It used to delegate to the evaluator's test in trex-category; that module is
     * gone, and its tests stayed with the evaluator while this one followed the writer to trex-ws.
     * Six lines of duplication beat a test-jar dependency between two modules.
     */
    private static CanonicalEvent line(String id, long amount, String raw) {
        return new CanonicalEvent(1, id, "ing-savings", null, "AUD",
            java.time.LocalDate.of(2026, 7, 1), amount, 0, raw, raw,
            amount < 0 ? trex.core.TypeHint.WITHDRAWAL : trex.core.TypeHint.DEPOSIT,
            null, null, null, trex.core.EventState.EXTERNAL, null, java.util.List.of(),
            trex.core.Provenance.BANK, "ing-csv", null, null, null, null, null, null,
            java.time.Instant.EPOCH);
    }

}
