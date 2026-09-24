package trex.egress.firefly;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The cache is an accelerator only because these two reads work: everything it holds has to come
 * back out of Firefly. If either of these is wrong, deleting the cache stops being free and the
 * whole "ephemeral" decision (SPEC §5.8) is void.
 */
class RecoveryTest {

    private static FireflyClient.Existing existing(List<String> tags, String notes) {
        return new FireflyClient.Existing("100", "abc", "GROCERIES", tags,
            FireflyClient.journalN(notes), null);
    }

    /**
     * What trex last said comes from OUR tag, never from Firefly's category field — the category
     * may have been edited by hand, and telling the two apart is what preserves that edit.
     */
    @Test
    void theLastProjectedCategoryComesFromTheTag() {
        assertEquals("SHOPPING", FireflyEgress.projectedCategory(
            existing(List.of("trex", "trex-category:SHOPPING"), "")));
        assertEquals("SHOPPING", FireflyEgress.projectedCategory(
            existing(List.of("trex-category:SHOPPING", "holiday", "trex"), "")),
            "order does not matter; other tags are yours and are ignored");
    }

    /** No tag means we cannot know what we last said — and the caller must then not overwrite. */
    @Test
    void aMissingTagIsEmptyRatherThanAGuess() {
        assertEquals("", FireflyEgress.projectedCategory(existing(List.of("trex"), "")));
        assertEquals("", FireflyEgress.projectedCategory(existing(List.of(), "")));
    }

    /** n lives in the notes because the cache is not allowed to be the only record of it. */
    @Test
    void theJournalLineIsRecoverableFromTheNotes() {
        assertEquals(1234, FireflyClient.journalN("trex n=1234 rules=83f272f8\nAMAZON AU RETAIL"));
        assertEquals(1, FireflyClient.journalN("trex n=1 rules=none"));
    }

    /** Notes you have typed yourself must not be mistaken for ours. */
    @Test
    void notesWithoutOurMarkerYieldNothing() {
        assertEquals(-1, FireflyClient.journalN("bought a birthday present, n=99"));
        assertEquals(-1, FireflyClient.journalN(""));
        assertEquals(-1, FireflyClient.journalN(null));
    }
}
