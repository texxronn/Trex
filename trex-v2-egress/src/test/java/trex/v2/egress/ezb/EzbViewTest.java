package trex.v2.egress.ezb;

import org.junit.jupiter.api.Test;
import trex.v2.egress.hub.FakeHub;
import trex.v2.egress.hub.HubClient;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ezbookkeeping view end to end over {@code FakeHub} + {@code FakeEzb} (V2-SPEC.md §11.2): plan
 * writes nothing, apply then verify is clean, the declared-wallet opening is projected, a second
 * apply is idempotent with a fresh session id, and the count check is the real gate.
 */
class EzbViewTest {

    private static final ZoneId SYD = ZoneId.of("Australia/Sydney");

    private static EzbMap map() {
        Map<String, EzbMap.Entry> byRef = new LinkedHashMap<>();
        byRef.put("ing-savings", new EzbMap.Entry("ING Savings", 8));
        byRef.put("ing-orange", new EzbMap.Entry("ING Orange Everyday", 2));
        byRef.put("cash-ron", new EzbMap.Entry("Cash (Ron)", 1));
        return new EzbMap(byRef, SYD);
    }

    private static EzbView.Outcome run(FakeHub hub, FakeEzb fake, PrintStream out, EzbView.Mode mode)
            throws Exception {
        return new EzbView(new HubClient(hub.url()), new EzbClient(fake.url(), "token", SYD), map(), out)
            .run(mode);
    }

    private static EzbView.Outcome run(FakeHub hub, FakeEzb fake, PrintStream out, EzbView.Mode mode,
                                       EzbClient.Credentials credentials) throws Exception {
        return new EzbView(new HubClient(hub.url()), new EzbClient(fake.url(), "token", SYD), map(), out,
            credentials).run(mode);
    }

    private static PrintStream quiet() {
        return new PrintStream(PrintStream.nullOutputStream());
    }

    private static Map<String, Object> rowOf(FakeEzb fake, String accountId, int type) {
        return fake.transactions().stream()
            .filter(t -> type == ((Number) t.get("type")).intValue())
            .filter(t -> accountId.equals(t.get("sourceAccountId")))
            .findFirst().orElseThrow();
    }

    @Test
    void theFastClearIsUsedWhenCredentialsAreConfigured() throws Exception {
        try (FakeEzb fake = new FakeEzb(); FakeHub hub = new FakeHub()) {
            seedUnits(hub);                  // 4 rows, so the apply clears and re-imports in one call
            EzbClient.Credentials creds = new EzbClient.Credentials("ron", "secret");

            EzbView.Outcome first = run(hub, fake, quiet(), EzbView.Mode.APPLY, creds);
            assertEquals(4, first.imported());
            assertEquals(0, fake.deletes.get(), "no per-id delete in fast mode");
            assertEquals(1, fake.dataClearCalls.get(), "one clear call per apply");

            EzbView.Outcome second = run(hub, fake, quiet(), EzbView.Mode.APPLY, creds);
            assertEquals(4, second.imported(), "idempotent");
            assertEquals(2, fake.dataClearCalls.get(), "one call per apply, not per row");

            assertTrue(run(hub, fake, quiet(), EzbView.Mode.VERIFY, creds).mismatches().isEmpty());
        }
    }

    @Test
    void theSlowClearDeletesByIdWithoutCredentials() throws Exception {
        try (FakeEzb fake = new FakeEzb(); FakeHub hub = new FakeHub()) {
            seedUnits(hub);
            run(hub, fake, quiet(), EzbView.Mode.APPLY);   // populates 4 rows (nothing to clear yet)
            run(hub, fake, quiet(), EzbView.Mode.APPLY);   // no credentials -> deletes each by id
            assertTrue(fake.deletes.get() >= 4, "each row was deleted by id");
            assertEquals(0, fake.dataClearCalls.get(), "the one-call clear is never reached");
            assertTrue(fake.sessionTokens.isEmpty(), "no login was attempted");
        }
    }

    @Test
    void planReportsWithoutWriting() throws Exception {
        try (FakeEzb fake = new FakeEzb(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(
                FakeHub.unit("e1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -1234, "GROCERIES",
                    "COLES", "h1"),
                FakeHub.unit("e2", "EXTERNAL", 2, "ing-savings", null, "2026-09-02", 2500, "SALARY",
                    "ACME", "h2"),
                FakeHub.transfer("TRF-1", 3, "ing-orange", "ing-savings", "2026-09-03", 500, "l1", "l2"));
            hub.openings = List.of(FakeHub.opening("ing-savings", false, 0, null, 4000),
                FakeHub.opening("ing-orange", false, 0, null, 0));

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            EzbView.Outcome outcome = run(hub, fake, new PrintStream(bytes, true, StandardCharsets.UTF_8),
                EzbView.Mode.PLAN);

            assertEquals(1, outcome.openings(), "ing-savings opens at 4000; ing-orange's 0 is skipped");
            assertEquals(1, outcome.expenses());
            assertEquals(1, outcome.incomes());
            assertEquals(1, outcome.transfers());
            assertEquals(0, outcome.accountsCreated());
            assertEquals(0, outcome.imported());

            assertTrue(fake.accounts().isEmpty(), "plan creates no account");
            assertTrue(fake.transactions().isEmpty(), "plan imports nothing");
            assertTrue(fake.createdCategoryNames.isEmpty(), "plan creates no category");
            assertTrue(fake.clientSessionIds.isEmpty(), "plan never imports");

            String text = bytes.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("unmapped refs: none"), text);
            assertTrue(text.contains("ING Savings"), text);
            assertTrue(text.contains("GROCERIES"), text);
        }
    }

    private static void seedUnits(FakeHub hub) {
        hub.units = List.of(
            FakeHub.unit("e1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -1234, "GROCERIES",
                "COLES", "h1"),
            FakeHub.unit("e2", "EXTERNAL", 2, "ing-savings", null, "2026-09-02", 2500, "SALARY",
                "ACME", "h2"),
            FakeHub.transfer("TRF-1", 3, "ing-orange", "ing-savings", "2026-09-03", 500, "l1", "l2"));
        hub.openings = List.of(FakeHub.opening("ing-savings", false, 0, null, 4000),
            FakeHub.opening("ing-orange", false, 0, null, 0));
    }

    @Test
    void applyThenVerifyIsClean() throws Exception {
        try (FakeEzb fake = new FakeEzb(); FakeHub hub = new FakeHub()) {
            seedUnits(hub);
            EzbView.Outcome applied = run(hub, fake, quiet(), EzbView.Mode.APPLY);

            assertEquals(2, applied.accountsCreated());
            assertEquals(4, applied.imported(), "one opening plus three units");
            assertEquals(0, applied.cleared());

            long savings = fake.balance(fake.accountIdByName().get("ING Savings"));
            assertEquals(4000 - 1234 + 2500 + 500, savings);

            EzbView.Outcome verified = run(hub, fake, quiet(), EzbView.Mode.VERIFY);
            assertTrue(verified.mismatches().isEmpty(), verified.mismatches().toString());
        }
    }

    @Test
    void aSecondApplyHasTheSameCountsAndAFreshSessionId() throws Exception {
        try (FakeEzb fake = new FakeEzb(); FakeHub hub = new FakeHub()) {
            seedUnits(hub);
            run(hub, fake, quiet(), EzbView.Mode.APPLY);
            List<String> first = List.copyOf(fake.clientSessionIds);
            int rows = fake.transactionIds().size();

            EzbView.Outcome second = run(hub, fake, quiet(), EzbView.Mode.APPLY);

            List<String> latest = fake.clientSessionIds.subList(first.size(), fake.clientSessionIds.size());
            assertFalse(latest.isEmpty());
            assertTrue(latest.stream().noneMatch(first::contains),
                "a fresh clientSessionId per call, never reused across applies");
            assertEquals(fake.clientSessionIds.size(),
                new java.util.HashSet<>(fake.clientSessionIds).size(),
                "every import call gets its own id: the checker drops a repeated one");
            assertEquals(0, fake.duplicateImports.get(), "no import was dropped as a repeat submission");
            assertEquals(rows, fake.transactionIds().size(), "idempotent");
            assertEquals(rows, second.imported());
            assertEquals(0, second.accountsCreated(), "nothing to create the second time");
            assertEquals(0, second.categoriesCreated(), "ids are reused, not recreated");
        }
    }

    @Test
    void theDeclaredWalletOpeningIsProjectedAndVerifies() throws Exception {
        try (FakeEzb fake = new FakeEzb(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(
                FakeHub.transfer("TRF-1", 1, "ing-orange", "cash-ron", "2026-09-01", 500, "l1", "l2"),
                FakeHub.unit("c1", "EXTERNAL", 2, "cash-ron", null, "2026-09-02", -200, "-", "SPEND", "h1"),
                FakeHub.unit("c2", "EXTERNAL", 3, "cash-ron", null, "2026-09-10", -100, "-", "LATER", "h2"));
            hub.openings = List.of(
                FakeHub.opening("cash-ron", true, 700, "2026-09-03", 0),
                FakeHub.opening("ing-orange", false, 0, null, 0));

            run(hub, fake, quiet(), EzbView.Mode.APPLY);

            String cashId = fake.accountIdByName().get("Cash (Ron)");
            assertEquals(400, ((Number) rowOf(fake, cashId, 1).get("sourceAmount")).longValue(),
                "700 − (500 − 200) = 400");
            assertEquals(600, fake.balance(cashId), "400 + 500 − 200 − 100");

            assertTrue(run(hub, fake, quiet(), EzbView.Mode.VERIFY).mismatches().isEmpty());
        }
    }

    @Test
    void aUnitNamingAnUnmappedRefIsRefusedBeforeAnyWrite() throws Exception {
        try (FakeEzb fake = new FakeEzb(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("e1", "EXTERNAL", 1, "zzz-unknown", null, "2026-09-01",
                -100, "X", "SHOP", "h1"));

            EzbView.Refused e = assertThrows(EzbView.Refused.class,
                () -> run(hub, fake, quiet(), EzbView.Mode.APPLY));
            assertTrue(e.getMessage().contains("zzz-unknown"), e.getMessage());
            assertTrue(e.getMessage().contains("nothing has been written"), e.getMessage());

            assertTrue(fake.accounts().isEmpty());
            assertTrue(fake.transactions().isEmpty());
        }
    }

    @Test
    void aVerifyDiffIsReportedAndNothingIsWritten() throws Exception {
        try (FakeEzb fake = new FakeEzb(); FakeHub hub = new FakeHub()) {
            seedUnits(hub);
            run(hub, fake, quiet(), EzbView.Mode.APPLY);
            int deleted = fake.deletes.get();
            int sessions = fake.clientSessionIds.size();
            int accounts = fake.createdAccountNames.size();

            fake.drift(fake.accountIdByName().get("ING Savings"), 100);

            EzbView.Outcome verified = run(hub, fake, quiet(), EzbView.Mode.VERIFY);
            assertFalse(verified.mismatches().isEmpty());
            assertTrue(verified.mismatches().stream().anyMatch(m -> m.startsWith("ing-savings: expected")),
                verified.mismatches().toString());

            assertEquals(sessions, fake.clientSessionIds.size(), "verify imports nothing");
            assertEquals(deleted, fake.deletes.get(), "verify deletes nothing");
            assertEquals(accounts, fake.createdAccountNames.size(), "verify creates nothing");
        }
    }

    @Test
    void aShortImportIsCaughtByThePostImportCountCheck() throws Exception {
        try (FakeEzb fake = new FakeEzb(); FakeHub hub = new FakeHub()) {
            seedUnits(hub);
            fake.dropRows = 1;

            EzbView.Refused e = assertThrows(EzbView.Refused.class,
                () -> run(hub, fake, quiet(), EzbView.Mode.APPLY));
            assertTrue(e.getMessage().contains("holds"), e.getMessage());
        }
    }

    @Test
    void anExistingCategoryIdSurvivesTheApply() throws Exception {
        try (FakeEzb fake = new FakeEzb(); FakeHub hub = new FakeHub()) {
            // A "saved exploration": the ids ezbookkeeping already holds must be reused, not replaced.
            fake.seedCategory("p-expense", "trex-expense", 2, "0");
            fake.seedCategory("saved-groceries", "GROCERIES", 2, "p-expense");
            hub.units = List.of(FakeHub.unit("e1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1234, "GROCERIES", "COLES", "h1"));
            hub.openings = List.of();

            EzbView.Outcome outcome = run(hub, fake, quiet(), EzbView.Mode.APPLY);

            assertEquals(0, outcome.categoriesCreated(), "the primary and child already exist");
            assertFalse(fake.createdCategoryNames.contains("GROCERIES"));
            assertTrue(fake.categories().stream().anyMatch(c -> c.id().equals("saved-groceries")));
            assertEquals("saved-groceries", rowOf(fake, fake.accountIdByName().get("ING Savings"), 3)
                .get("categoryId"), "the row names the id the exploration holds");
        }
    }

    @Test
    void aPrimaryCategoryIsCreatedOncePerTypeAndChildrenUnderIt() throws Exception {
        try (FakeEzb fake = new FakeEzb(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(
                FakeHub.unit("e1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -1234, "GROCERIES",
                    "COLES", "h1"),
                FakeHub.unit("e2", "EXTERNAL", 2, "ing-savings", null, "2026-09-02", 2500, "SALARY",
                    "ACME", "h2"),
                FakeHub.transfer("TRF-1", 3, "ing-orange", "ing-savings", "2026-09-03", 500, "l1", "l2"));
            hub.openings = List.of();

            EzbView.Outcome outcome = run(hub, fake, quiet(), EzbView.Mode.APPLY);

            assertEquals(6, outcome.categoriesCreated(),
                "3 primaries + GROCERIES + SALARY + TRANSFER");
            assertTrue(fake.createdCategoryNames.contains("trex-income"));
            assertTrue(fake.createdCategoryNames.contains("trex-expense"));
            assertTrue(fake.createdCategoryNames.contains("trex-transfer"));
        }
    }
}
