package trex.v2.egress.firefly;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §15.21: category seeding fills empty notes only and never overwrites what you typed. */
class CategorySeedingTest {

    @Test
    void createsMissingCategoriesAndFillsEmptyNotesOnly() throws Exception {
        try (FakeFirefly fake = new FakeFirefly()) {
            fake.categoryNotes.put("GROCERIES", "");              // exists, no notes
            fake.categoryNotes.put("FUEL", "typed by me");         // exists, yours
            Map<String, String> comments = Map.of(
                "GROCERIES", "food and drink",
                "FUEL", "petrol rules",
                "TAXES", "ATO instalments");

            CategorySeeding.Result result = CategorySeeding.seed(new FireflyClient(fake.url(), "token"),
                List.of("GROCERIES", "FUEL", "TAXES"), comments);

            assertEquals(1, result.created(), "only TAXES was missing");
            assertEquals(1, result.annotated(), "only GROCERIES had empty notes");
            assertTrue(fake.createdCategories.contains("TAXES"));
            assertEquals("food and drink", fake.categoryNotes.get("GROCERIES"));
            assertEquals("typed by me", fake.categoryNotes.get("FUEL"), "your notes are yours");
            assertEquals("ATO instalments", fake.categoryNotes.get("TAXES"));
        }
    }
}
