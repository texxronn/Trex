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
            .flatMap(g -> g.splits().stream())
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
    void splitsAndGroupTitleSurviveARetag() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            // A group that was projected, then hand-split into two lines with a title.
            Map<String, Object> first = new java.util.LinkedHashMap<>();
            first.put("external_id", "ext1");
            first.put("category_name", "GROCERIES");
            first.put("tags", List.of("trex", "trex-category:GROCERIES"));
            first.put("amount", "6.00");
            Map<String, Object> second = new java.util.LinkedHashMap<>(first);
            second.put("amount", "4.00");
            String groupId = fake.seedGroup("ext1", "Weekly shop", List.of(first, second));

            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -1000,
                "FOOD", "COLES 1234", "h1"));
            hub.projection.put("ext1", Map.of("unitId", "ext1", "unitKind", "EXTERNAL", "groupId", groupId,
                "category", "GROCERIES", "stateHash", "", "configRevision", "cfg", "deriveVersion",
                "derive/1", "verifiedAt", "now"));

            FireflyEgress.Outcome outcome = egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertEquals(1, outcome.retags());

            Map<String, Object> body = fake.lastPutBody;
            assertEquals("Weekly shop", body.get("group_title"), "the title survives read-modify-write");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> splits = (List<Map<String, Object>>) body.get("transactions");
            assertEquals(2, splits.size(), "both splits survive read-modify-write");
            for (Map<String, Object> split : splits) {
                assertEquals("FOOD", split.get("category_name"));
                assertTrue(((List<?>) split.get("tags")).contains("trex-category:FOOD"));
            }
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
    void anUnmappedClearingAccountStopsBeforeAnyWrite() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(
                FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -1000, "GROCERIES",
                    "COLES 1234", "h1"),
                FakeHub.unit("TRF-c", "TRANSFER", 2, "ing-orange", "nab-fixed", "2026-09-01", 3180000,
                    "TRANSFER", "NAB Fixed Payments", "h2"));
            FireflyEgress.Refused refused = org.junit.jupiter.api.Assertions.assertThrows(
                FireflyEgress.Refused.class,
                () -> egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run());
            assertTrue(refused.getMessage().contains("nab-fixed"), refused.getMessage());
            assertEquals(0, fake.posts.get(), "nothing written");
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

    @Test
    void aForeignGroupWithAnExternalIdIsNeverOurs() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            fake.seedGroup("importer-77", null, List.of(new java.util.HashMap<>(Map.of(
                "external_id", "importer-77", "description", "Imported by hand",
                "tags", List.of("imported"), "category_name", "FOOD"))));
            FireflyEgress.Outcome verified = egress(hub, fake, accounts(), FireflyEgress.Mode.VERIFY, true).run();
            assertEquals(0, verified.orphans(), "not ours, so not an orphan");
            assertEquals(1, fake.groups().size(), "and never deleted, even with --remove-orphans");
        }
    }

    @Test
    void aRetagKeepsTagsYouAddedInFirefly() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            String gid = fake.seedGroup("ext1", null, List.of(new java.util.HashMap<>(Map.of(
                "external_id", "ext1", "type", "withdrawal", "date", "2026-09-01", "amount", "10.00",
                "currency_code", "AUD", "source_id", "1", "destination_name", "COLES",
                "description", "COLES 1234", "category_name", "GROCERIES",
                "tags", List.of("trex", "trex-category:GROCERIES", "holiday")))));
            hub.projection.put("ext1", Map.of("unitId", "ext1", "unitKind", "EXTERNAL", "groupId", gid,
                "category", "GROCERIES", "stateHash", "", "configRevision", "cfg", "deriveVersion", "d",
                "verifiedAt", "t"));
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "FOOD", "COLES 1234", "h1"));
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertTrue(((List<?>) splitOf(fake, "ext1").get("tags")).contains("holiday"));
        }
    }

    /** A group as a previous run left it, and the state that run recorded. */
    private static String projected(FakeFirefly fake, FakeHub hub, String unitId, String amount,
                                    String category, String stateHash) {
        Map<String, Object> split = new java.util.HashMap<>(Map.ofEntries(
            Map.entry("external_id", unitId), Map.entry("type", "withdrawal"),
            Map.entry("date", "2026-09-01T00:00:00+10:00"), Map.entry("amount", amount),
            Map.entry("currency_code", "AUD"), Map.entry("source_id", "1"),
            Map.entry("source_name", "ING Savings"), Map.entry("destination_id", "901"),
            Map.entry("destination_name", "COLES"), Map.entry("description", "COLES 1234"),
            Map.entry("category_name", category),
            Map.entry("tags", List.of("trex", "trex-category:" + category))));
        String gid = fake.seedGroup(unitId, null, List.of(split));
        hub.projection.put(unitId, Map.of("unitId", unitId, "unitKind", "EXTERNAL", "groupId", gid,
            "category", category, "stateHash", stateHash, "configRevision", "cfg", "deriveVersion", "d",
            "verifiedAt", "t"));
        return gid;
    }

    @Test
    void aRestatedAmountReachesFirefly() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            projected(fake, hub, "ext1", "10.00", "GROCERIES", "fp1:stale");
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1200, "GROCERIES", "COLES 1234", "h2"));
            FireflyEgress.Outcome out = egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertEquals(1, out.updates());
            assertEquals(0, out.retags(), "a content move is not a retag (F10)");
            assertEquals(0, new java.math.BigDecimal("12.00").compareTo(
                new java.math.BigDecimal((String) splitOf(fake, "ext1").get("amount"))));
            assertTrue(egress(hub, fake, accounts(), FireflyEgress.Mode.PLAN, false).run().empty());
            assertTrue(egress(hub, fake, accounts(), FireflyEgress.Mode.VERIFY, false).run().empty(),
                "and a rebuilt state agrees (F3)");
        }
    }

    @Test
    void verifyFindsContentDriftThatTheStateDoesNotKnowAbout() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "GROCERIES", "COLES 1234", "h1"));
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            splitOf(fake, "ext1").put("amount", "99.000000000000");   // edited in Firefly
            assertFalse(egress(hub, fake, accounts(), FireflyEgress.Mode.VERIFY, false).run().empty());
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertEquals(0, new java.math.BigDecimal("10.00").compareTo(
                new java.math.BigDecimal((String) splitOf(fake, "ext1").get("amount"))), "trex wins (D2)");
        }
    }

    @Test
    void aHandSplitGroupIsNeverRewrittenAndVerifiesClean() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            Map<String, Object> a = new java.util.HashMap<>(Map.of("external_id", "ext1", "type", "withdrawal",
                "date", "2026-09-01T00:00:00+10:00", "amount", "6.00", "currency_code", "AUD",
                "source_id", "1", "destination_name", "COLES", "description", "food",
                "category_name", "GROCERIES", "tags", List.of("trex", "trex-category:GROCERIES")));
            Map<String, Object> b = new java.util.HashMap<>(a);
            b.put("amount", "4.00");
            b.put("description", "soap");
            String gid = fake.seedGroup("ext1", "COLES 1234", List.of(a, b));
            hub.projection.put("ext1", Map.of("unitId", "ext1", "unitKind", "EXTERNAL", "groupId", gid,
                "category", "GROCERIES", "stateHash", "", "configRevision", "cfg", "deriveVersion", "d",
                "verifiedAt", "t"));
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1200, "GROCERIES", "COLES 1234", "h2"));     // the bank restated 10.00 -> 12.00
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            var splits = fake.groups().get(gid).splits();
            assertEquals(2, splits.size());
            assertEquals("6.00", splits.get(0).get("amount"), "your split stays as you made it");
            assertEquals(Content.HAND_SPLIT, hub.projection.get("ext1").get("stateHash"));
            assertTrue(egress(hub, fake, accounts(), FireflyEgress.Mode.VERIFY, false).run().empty(),
                "a hand-split group does not fail verify forever");
        }
    }

    @Test
    void oldStateHashesAreCheckedOnceWithoutWrites() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "GROCERIES", "COLES 1234", "h1"));
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            Map<String, Object> old = new java.util.HashMap<>(hub.projection.get("ext1"));
            old.put("stateHash", "9f2c-an-old-hub-unit-hash");
            hub.projection.put("ext1", old);
            int puts = fake.puts.get();
            FireflyEgress.Outcome first = egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertEquals(puts, fake.puts.get(), "Firefly already matches: nothing written");
            assertEquals(1, first.unchanged());
            assertTrue(egress(hub, fake, accounts(), FireflyEgress.Mode.PLAN, false).run().empty(), "and quiet after");
        }
    }

    @Test
    void aDuplicateCreateConvergesTheExistingGroup() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            fake.seedGroup("ext1", null, List.of(new java.util.HashMap<>(Map.of("external_id", "ext1",
                "type", "withdrawal", "date", "2026-09-01T00:00:00+10:00", "amount", "10.00",
                "currency_code", "AUD", "source_id", "1", "destination_name", "COLES",
                "description", "COLES 1234", "category_name", "GROCERIES",
                "tags", List.of("trex", "trex-category:GROCERIES")))));
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "FOOD", "COLES 1234", "h1"));      // state lost; trex has since moved the category
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertEquals("FOOD", splitOf(fake, "ext1").get("category_name"), "F7: not left stale");
        }
    }

    @Test
    void aDeletedGroupStopsThePassWithANamedRefusal() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "GROCERIES", "COLES 1234", "h1"));
            AccountMap accounts = accounts();
            egress(hub, fake, accounts, FireflyEgress.Mode.APPLY, false).run();
            String gid = (String) hub.projection.get("ext1").get("groupId");
            fake.groups().remove(gid);       // deleted in Firefly
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "FOOD", "COLES 1234", "h1"));      // and trex has moved the category
            FireflyEgress.Refused refused = org.junit.jupiter.api.Assertions.assertThrows(
                FireflyEgress.Refused.class,
                () -> egress(hub, fake, accounts, FireflyEgress.Mode.APPLY, false).run());
            assertTrue(refused.getMessage().contains("ext1"), refused.getMessage());
            assertTrue(refused.getMessage().contains(gid), refused.getMessage());
        }
    }

    @Test
    void aSupersededTransferLegRekeysTheGroup() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.transfer("TRF-old", 1, "ing-savings", "ing-orange", "2026-09-01", 500, "a", "b"));
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            // A re-parse supersedes leg a: the pair's id moves (V2-SPEC.md §4 hashes current ids).
            hub.resolved = Map.of("a", "a2");
            hub.units = List.of(FakeHub.transfer("TRF-new", 2, "ing-savings", "ing-orange", "2026-09-01", 500, "a2", "b"));
            FireflyEgress.Outcome out = egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertEquals(1, out.rekeys());
            assertEquals(0, out.creates(), "no second transfer");
            assertEquals(0, out.orphans());
            assertEquals(1, fake.groups().size());
            assertEquals("TRF-new", splitOf(fake, "TRF-new").get("external_id"));
            assertEquals(java.util.Set.of("TRF-new"), hub.projection.keySet(),
                "no stale alias: new and old rows are persisted in one replace");
            assertTrue(egress(hub, fake, accounts(), FireflyEgress.Mode.VERIFY, false).run().empty());
        }
    }

    @Test
    void aRemoveOrphansNeverDeletesAGroupACurrentRowStillUses() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.transfer("TRF-old", 1, "ing-savings", "ing-orange", "2026-09-01", 500, "a", "b"));
            AccountMap accounts = accounts();
            egress(hub, fake, accounts, FireflyEgress.Mode.APPLY, false).run();
            String gid = (String) hub.projection.get("TRF-old").get("groupId");
            // A stale alias: the new unit's row names the same group as the old (an interruption with an
            // older build, or a hand-written state). The old row must not delete the current group.
            hub.resolved = Map.of("a", "a2");
            hub.units = List.of(FakeHub.transfer("TRF-new", 2, "ing-savings", "ing-orange", "2026-09-01", 500, "a2", "b"));
            hub.projection.put("TRF-new", Map.of("unitId", "TRF-new", "unitKind", "TRANSFER", "groupId", gid,
                "category", "TRANSFER", "stateHash", Content.HAND_SPLIT, "configRevision", "cfg",
                "deriveVersion", "d", "verifiedAt", "t"));

            egress(hub, fake, accounts, FireflyEgress.Mode.APPLY, true).run();   // --remove-orphans
            assertEquals(1, fake.groups().size(), "the group a current row still uses is never deleted");
            assertFalse(hub.projection.containsKey("TRF-old"), "the stale alias leaves the accelerator");
        }
    }

    @Test
    void anUnpairIsReportedAsAReplacementNotRekeyed() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.transfer("TRF-1", 1, "ing-savings", "ing-orange", "2026-09-01", 500, "a", "b"));
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            hub.units = List.of(
                FakeHub.unit("a", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -500, "OTHER", "Transfer", "h"),
                FakeHub.unit("b", "EXTERNAL", 2, "ing-orange", null, "2026-09-01", 500, "OTHER", "Transfer", "h"));
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            new FireflyEgress(new HubClient(hub.url()), new FireflyClient(fake.url(), "token"), accounts(),
                FireflyEgress.Mode.PLAN, false, new PrintStream(bytes), "derive/1").run();
            String plan = bytes.toString();
            assertTrue(plan.contains("ORPHAN TRF-1") && plan.contains("replaced by a, b"), plan);
        }
    }

    @Test
    void anOrphanDeletedInFireflyIsGoneNotAnAbort() throws Exception {            // review V4
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.transfer("TRF-1", 1, "ing-savings", "ing-orange", "2026-09-01", 500, "a", "b"));
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            // You deleted it in Firefly. Delete through the client so the fake's external-id index is
            // dropped too: a direct map clear would leave a phantom duplicate answer behind (V4 note).
            String gid = (String) hub.projection.get("TRF-1").get("groupId");
            new FireflyClient(fake.url(), "token").deleteTransaction(gid);
            hub.units = List.of();                                   // and trex no longer has the unit
            FireflyEgress.Outcome plan = egress(hub, fake, accounts(), FireflyEgress.Mode.PLAN, false).run();
            assertEquals(0, plan.orphans(), "a gone group is not an orphan to remove");
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertTrue(hub.projection.isEmpty(), "and it leaves the accelerator");
        }
    }

    @Test
    void aSupersededUnitWhoseGroupLostItsTagIsCreatedNotStuck() throws Exception {   // review V5
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("old", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -1000,
                "GROCERIES", "COLES 1234", "h1"));
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            splitOf(fake, "old").put("tags", List.of("mine"));      // you took it over in Firefly
            hub.resolved = Map.of("old", "new");
            hub.units = List.of(FakeHub.unit("new", "EXTERNAL", 2, "ing-savings", null, "2026-09-01", -1000,
                "GROCERIES", "COLES 1234", "h2"));
            FireflyEgress.Outcome out = egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertEquals(0, out.rekeys());
            assertEquals(1, out.creates(), "the new unit lands");
            assertEquals(2, fake.groups().size(), "your group is left alone");
            assertTrue(egress(hub, fake, accounts(), FireflyEgress.Mode.PLAN, false).run().empty(),
                "and the next plan is quiet, not stuck");
        }
    }

    @Test
    void aSupersededHandSplitTransferRekeysItsIdentity() throws Exception {         // review R3
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.transfer("TRF-old", 1, "ing-savings", "ing-orange", "2026-09-01", 500, "a", "b"));
            AccountMap accounts = accounts();
            egress(hub, fake, accounts, FireflyEgress.Mode.APPLY, false).run();
            String gid = (String) hub.projection.get("TRF-old").get("groupId");

            // You split the group by hand into 30.00 + 20.00.
            Map<String, Object> first = new java.util.LinkedHashMap<>(fake.groups().get(gid).splits().get(0));
            Map<String, Object> second = new java.util.LinkedHashMap<>(first);
            first.put("amount", "30.00");
            second.put("amount", "20.00");
            fake.groups().put(gid, new FakeFirefly.Group(gid, null, List.of(first, second)));

            // A re-parse supersedes leg a: the pair's id moves (V2-SPEC.md §4 hashes current ids).
            hub.resolved = Map.of("a", "a2");
            hub.units = List.of(FakeHub.transfer("TRF-new", 2, "ing-savings", "ing-orange", "2026-09-01", 500, "a2", "b"));
            FireflyEgress.Outcome out = egress(hub, fake, accounts, FireflyEgress.Mode.APPLY, false).run();

            assertEquals(1, out.rekeys());
            assertEquals(0, out.creates(), "no second transfer");
            assertEquals(0, out.orphans());
            assertEquals(1, fake.groups().size(), "one group, re-keyed in place");
            List<Map<String, Object>> splits = fake.groups().get(gid).splits();
            assertEquals(2, splits.size(), "your hand-split survives");
            for (Map<String, Object> split : splits) {
                assertEquals("TRF-new", split.get("external_id"), "every split carries the new id");
            }
            assertEquals(0, new java.math.BigDecimal("30.00").compareTo(
                new java.math.BigDecimal((String) splits.get(0).get("amount"))), "your amounts are untouched");
            assertEquals(0, new java.math.BigDecimal("20.00").compareTo(
                new java.math.BigDecimal((String) splits.get(1).get("amount"))));
            assertEquals(Content.HAND_SPLIT, hub.projection.get("TRF-new").get("stateHash"));

            assertTrue(egress(hub, fake, accounts, FireflyEgress.Mode.VERIFY, false).run().empty(),
                "verify is empty after the re-key");

            // And a second plan -> apply -> verify pass stays empty.
            assertTrue(egress(hub, fake, accounts, FireflyEgress.Mode.PLAN, false).run().empty());
            egress(hub, fake, accounts, FireflyEgress.Mode.APPLY, false).run();
            assertTrue(egress(hub, fake, accounts, FireflyEgress.Mode.VERIFY, false).run().empty(),
                "and it stays empty a second pass");
        }
    }
}
