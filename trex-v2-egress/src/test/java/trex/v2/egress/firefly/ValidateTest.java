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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read-only contract checker (V2-FIREFLY-EGRESS-PLAN.md Task 4.4; D9/R2): a violation is
 * something Firefly changed, never something trex moved on from (review V1/V2). Every test also
 * pins that {@code --validate} writes nothing.
 */
class ValidateTest {

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
                                        FireflyEgress.Mode mode) {
        return new FireflyEgress(new HubClient(hub.url()), new FireflyClient(fake.url(), "token"),
            accounts, mode, false, new PrintStream(OutputStream.nullOutputStream()), "derive/1");
    }

    /** Run the read-only check against both fakes, exactly as the command does. */
    private static List<Validate.Finding> validate(FakeHub hub, FakeFirefly fake, AccountMap accounts)
            throws Exception {
        HubClient client = new HubClient(hub.url());
        return Validate.check(client.units(0), client.projection(),
            new FireflyClient(fake.url(), "token").inventory(), accounts);
    }

    private static List<String> kinds(List<Validate.Finding> findings) {
        return findings.stream().map(Validate.Finding::kind).toList();
    }

    private static Map<String, Object> splitOf(FakeFirefly fake, String externalId) {
        return fake.groups().values().stream()
            .flatMap(g -> g.splits().stream())
            .filter(s -> externalId.equals(s.get("external_id")))
            .findFirst().orElseThrow();
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
    void aDeletedGroupIsMissing() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "GROCERIES", "COLES 1234", "h1"));
            AccountMap accounts = accounts();
            egress(hub, fake, accounts, FireflyEgress.Mode.APPLY).run();
            String gid = (String) hub.projection.get("ext1").get("groupId");
            fake.groups().remove(gid);                       // deleted in Firefly

            List<Validate.Finding> findings = validate(hub, fake, accounts);
            assertEquals(List.of("MISSING"), kinds(findings), findings.toString());
            assertEquals("ext1", findings.getFirst().unitId());
            assertEquals(gid, findings.getFirst().groupId());
        }
    }

    @Test
    void aGroupWhoseTagWasRemovedIsUntagged() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            projected(fake, hub, "ext1", "10.00", "GROCERIES", "fp1:whatever");
            splitOf(fake, "ext1").put("tags", List.of("mine"));   // you took it over in Firefly
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "GROCERIES", "COLES 1234", "h1"));

            List<Validate.Finding> findings = validate(hub, fake, accounts());
            assertEquals(List.of("UNTAGGED"), kinds(findings), findings.toString());
            assertEquals("ext1", findings.getFirst().unitId());
        }
    }

    @Test
    void aHandEditedAmountIsDrift() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "GROCERIES", "COLES 1234", "h1"));
            AccountMap accounts = accounts();
            egress(hub, fake, accounts, FireflyEgress.Mode.APPLY).run();
            splitOf(fake, "ext1").put("amount", "99.000000000000");   // edited in Firefly

            List<Validate.Finding> findings = validate(hub, fake, accounts);
            assertEquals(List.of("DRIFT"), kinds(findings), findings.toString());
            assertEquals("ext1", findings.getFirst().unitId());
        }
    }

    @Test
    void aHandSplitSumMismatchIsHandSplit() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            Map<String, Object> a = new java.util.HashMap<>(Map.ofEntries(
                Map.entry("external_id", "ext1"), Map.entry("type", "withdrawal"),
                Map.entry("date", "2026-09-01T00:00:00+10:00"), Map.entry("amount", "6.00"),
                Map.entry("currency_code", "AUD"), Map.entry("source_id", "1"),
                Map.entry("destination_name", "COLES"), Map.entry("description", "food"),
                Map.entry("category_name", "GROCERIES"),
                Map.entry("tags", List.of("trex", "trex-category:GROCERIES")),
                Map.entry("notes", "trex n=1 cfg\nCOLES 1234")));
            Map<String, Object> b = new java.util.HashMap<>(a);
            b.put("amount", "4.00");
            b.put("description", "soap");
            String gid = fake.seedGroup("ext1", "COLES 1234", List.of(a, b));
            hub.projection.put("ext1", Map.of("unitId", "ext1", "unitKind", "EXTERNAL", "groupId", gid,
                "category", "GROCERIES", "stateHash", Content.HAND_SPLIT, "configRevision", "cfg",
                "deriveVersion", "d", "verifiedAt", "t"));
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1200, "GROCERIES", "COLES 1234", "h2"));     // the bank says 12.00

            List<Validate.Finding> findings = validate(hub, fake, accounts());
            assertEquals(List.of("HAND_SPLIT"), kinds(findings), findings.toString());
            assertEquals("ext1", findings.getFirst().unitId());
        }
    }

    @Test
    void validateWritesNothing() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "GROCERIES", "COLES 1234", "h1"));
            AccountMap accounts = accounts();
            egress(hub, fake, accounts, FireflyEgress.Mode.APPLY).run();

            int posts = fake.posts.get();
            int puts = fake.puts.get();
            int deletes = fake.deletes.get();
            Map<String, Map<String, Object>> before = Map.copyOf(hub.projection);

            validate(hub, fake, accounts);

            assertEquals(posts, fake.posts.get(), "no POST");
            assertEquals(puts, fake.puts.get(), "no PUT");
            assertEquals(deletes, fake.deletes.get(), "no DELETE");
            assertEquals(before, hub.projection, "the hub state is untouched");
        }
    }

    @Test
    void aRuleEditLeavesStaleNotesButNoViolation() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "GROCERIES", "COLES 1234", "h1"));
            AccountMap accounts = accounts();
            egress(hub, fake, accounts, FireflyEgress.Mode.APPLY).run();

            // A rule edit re-plans every unit but only writes the changed categories: the notes keep a
            // stale rules=, and a new observation moves n=. Neither is a Firefly-side edit (V1).
            hub.configRevision = "sha256:cfg2";
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 2, "ing-savings", null, "2026-09-01",
                -1000, "GROCERIES", "COLES 1234", "h1"));

            assertTrue(validate(hub, fake, accounts).isEmpty(), "stale rules= and a new n= are not violations");
        }
    }

    @Test
    void aTrexSideRestatementIsBehindNotDrift() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "GROCERIES", "COLES 1234", "h1"));
            AccountMap accounts = accounts();
            egress(hub, fake, accounts, FireflyEgress.Mode.APPLY).run();
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1200, "GROCERIES", "COLES 1234", "h2"));     // the bank restated 10.00 -> 12.00

            List<Validate.Finding> findings = validate(hub, fake, accounts);
            assertEquals(List.of("BEHIND"), kinds(findings), findings.toString());
            assertEquals("ext1", findings.getFirst().unitId());
        }
    }

    @Test
    void aMangledNotesLineIsTampered() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "GROCERIES", "COLES 1234", "h1"));
            AccountMap accounts = accounts();
            egress(hub, fake, accounts, FireflyEgress.Mode.APPLY).run();
            splitOf(fake, "ext1").put("notes", "my notes");       // our first line is gone

            List<Validate.Finding> findings = validate(hub, fake, accounts);
            assertEquals(List.of("TAMPERED"), kinds(findings), findings.toString());
            assertEquals("ext1", findings.getFirst().unitId());
        }
    }
}
