package trex.v2.egress.firefly;

import org.junit.jupiter.api.Test;
import trex.v2.egress.hub.FakeHub;
import trex.v2.egress.hub.HubClient;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Firefly convergence gates (V2-PROPOSAL.md §15.8, §15.20): apply then plan is empty, a resume
 * does not re-post, a hand edit is preserved, and a de-projected unit is reported, never deleted.
 */
class FireflyEgressTest {

    private static AccountMap accounts() throws Exception {
        Path file = Files.createTempFile("firefly", ".yaml");
        Files.writeString(file, """
            accounts:
              ing-savings:   { name: "ING Savings",   type: asset }
              ing-orange:    { name: "ING Orange",    type: asset }
            """);
        return AccountMap.load(file).resolved(Map.of("ING Savings", "1", "ING Orange", "2"));
    }

    private static FireflyEgress egress(FakeHub hub, FakeFirefly fake, AccountMap accounts,
                                        FireflyEgress.Mode mode, boolean removeOrphans) {
        return new FireflyEgress(new HubClient(hub.url()), new FireflyClient(fake.url(), "token"),
            accounts, mode, removeOrphans, new PrintStream(OutputStream.nullOutputStream()), "derive/1");
    }

    private static Map<String, Object> splitOf(FakeFirefly fake, String externalId) {
        return fake.groups().values().stream()
            .map(FakeFirefly.Group::split)
            .filter(s -> externalId.equals(s.get("external_id")))
            .findFirst().orElseThrow();
    }

    @Test
    void appliesThenPlansEmptyAndResumesWithoutReposting() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(
                FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -1000, "GROCERIES",
                    "COLES 1234", "h1"),
                FakeHub.unit("TRF-x", "TRANSFER", 2, "ing-savings", "ing-orange", "2026-09-01", -500,
                    "TRANSFER", "Transfer to Savings", "h2"));
            AccountMap accounts = accounts();

            FireflyEgress.Outcome applied = egress(hub, fake, accounts, FireflyEgress.Mode.APPLY, false).run();
            assertEquals(2, applied.creates());
            assertEquals(2, hub.projection.size(), "state recorded as each write landed");

            assertTrue(egress(hub, fake, accounts, FireflyEgress.Mode.PLAN, false).run().empty(),
                "after apply, plan is empty");

            int posts = fake.posts.get();
            egress(hub, fake, accounts, FireflyEgress.Mode.APPLY, false).run();
            assertEquals(posts, fake.posts.get(), "a resume re-posts nothing");

            assertTrue(egress(hub, fake, accounts, FireflyEgress.Mode.VERIFY, false).run().empty(),
                "a state rebuilt from Firefly converges");
        }
    }

    @Test
    void aHandEditedCategoryIsPreservedAndTheStaleIdCleared() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -1000,
                "GROCERIES", "COLES 1234", "h1"));
            AccountMap accounts = accounts();
            egress(hub, fake, accounts, FireflyEgress.Mode.APPLY, false).run();

            Map<String, Object> split = splitOf(fake, "ext1");
            split.put("category_name", "MY EDIT");
            split.put("category_id", "99");

            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -1000,
                "FOOD", "COLES 1234", "h1"));
            FireflyEgress.Outcome outcome = egress(hub, fake, accounts, FireflyEgress.Mode.APPLY, false).run();

            assertEquals(1, outcome.preserved(), "your edit was left alone");
            assertEquals("MY EDIT", splitOf(fake, "ext1").get("category_name"));
            assertTrue(((List<?>) splitOf(fake, "ext1").get("tags")).contains("trex-category:FOOD"));
        }
    }

    @Test
    void aStaleCategoryIdIsClearedWhenTrexMovesTheCategory() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -1000,
                "GROCERIES", "COLES 1234", "h1"));
            AccountMap accounts = accounts();
            egress(hub, fake, accounts, FireflyEgress.Mode.APPLY, false).run();

            // Firefly returns category_id beside category_name; the tag still matches, so this is
            // our category to move and the id must be cleared or the name change is a silent no-op.
            Map<String, Object> split = splitOf(fake, "ext1");
            split.put("category_id", "99");
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -1000,
                "FOOD", "COLES 1234", "h1"));
            FireflyEgress.Outcome outcome = egress(hub, fake, accounts, FireflyEgress.Mode.APPLY, false).run();

            assertEquals(0, outcome.preserved());
            assertEquals("FOOD", splitOf(fake, "ext1").get("category_name"));
            assertNull(splitOf(fake, "ext1").get("category_id"),
                "a stale category_id is cleared so the name is authoritative");
        }
    }

    @Test
    void aRefusalStopsThePassNamingTheTransaction() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -1000,
                "GROCERIES", "COLES 1234", "h1"));
            fake.forcedStatuses.add(422);
            FireflyEgress.Refused refused = org.junit.jupiter.api.Assertions.assertThrows(
                FireflyEgress.Refused.class,
                () -> egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run());
            assertTrue(refused.getMessage().contains("ext1"), refused.getMessage());
            assertEquals(0, hub.projection.size(), "nothing is recorded for a refused write");
        }
    }

    @Test
    void aDeprojectedUnitIsReportedAsAnOrphanNeverDeletedAutomatically() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(
                FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -1000, "GROCERIES", "A", "h1"),
                FakeHub.unit("ext2", "EXTERNAL", 2, "ing-savings", null, "2026-09-02", -2000, "GROCERIES", "B", "h2"));
            AccountMap accounts = accounts();
            egress(hub, fake, accounts, FireflyEgress.Mode.APPLY, false).run();

            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -1000,
                "GROCERIES", "A", "h1"));

            assertEquals(1, egress(hub, fake, accounts, FireflyEgress.Mode.PLAN, false).run().orphans());
            egress(hub, fake, accounts, FireflyEgress.Mode.APPLY, false).run();
            assertEquals(2, fake.groups().size(), "an orphan is reported, not deleted");

            FireflyEgress.Outcome removed = egress(hub, fake, accounts, FireflyEgress.Mode.APPLY, true).run();
            assertEquals(1, removed.removed());
            assertEquals(1, fake.groups().size());
            assertFalse(hub.projection.containsKey("ext2"), "the accelerator matches reality again");
        }
    }
}
