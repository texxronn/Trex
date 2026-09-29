package trex.v2.egress.firefly;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Seeding the declared categories into Firefly (V2-PROPOSAL.md §11.3): create the ones it lacks,
 * carrying the rule comment as the category's notes, and fill notes only where they are empty.
 * Anything you have typed there is yours; an egress that overwrites it would be doing the thing the
 * compare-and-swap on categories exists to prevent.
 */
public final class CategorySeeding {

    public record Result(int created, int annotated) {}

    private CategorySeeding() {}

    public static Result seed(FireflyClient firefly, List<String> declared, Map<String, String> comments)
            throws IOException, InterruptedException {
        Map<String, FireflyClient.CategoryInfo> existing = firefly.categories();
        int created = 0;
        int annotated = 0;
        for (String category : declared) {
            String notes = comments.get(category);
            FireflyClient.CategoryInfo info = existing.get(category);
            if (info == null) {
                firefly.createCategory(category, notes);
                created++;
            } else if (!info.hasNotes() && notes != null && !notes.isBlank()) {
                firefly.setCategoryNotes(info.id(), notes);
                annotated++;
            }
        }
        return new Result(created, annotated);
    }
}
