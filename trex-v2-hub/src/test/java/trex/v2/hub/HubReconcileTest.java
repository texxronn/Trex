package trex.v2.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.hub.api.ChainsResponse;
import trex.v2.hub.api.ReconcileResponse;
import trex.v2.log.JsonlJournal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The balance check runs over {@code transaction} rows only and names every excluded {@code noop}
 * row with what classified it (V2-PROPOSAL.md §6.9): a profile rule, or the decision that overrode
 * it.
 */
class HubReconcileTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    @Test
    void aProfileNoopIsExcludedFromTheChainAndNamedWithItsReason(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir, true);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "a", -100, 900, "Opening spend"),
                fact(2, "b", 500, 1400, "Deposit"),
                fact(3, "fee", -29900, 0, "Orange Advantage annual fee")));
        }

        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 3L);
            ReconcileResponse r = hub.reconcile();
            assertTrue(r.ok(), "the chain closes once the noop row is excluded");
            ReconcileResponse.AccountJson acct = r.accounts().stream()
                .filter(a -> a.accountRef().equals("ing-savings")).findFirst().orElseThrow();
            assertEquals("reconciled", acct.status());
            assertEquals(1000, acct.opening());
            assertEquals(1400, acct.closing());
            assertEquals(1, acct.exclusions().size());
            ReconcileResponse.ExclusionJson ex = acct.exclusions().getFirst();
            assertEquals("fee", ex.externalId());
            assertEquals("profile", ex.classifiedBy());
            assertEquals("a statement snapshot, not a posting", ex.reason());

            // The Blotter carries the role and can filter on it: the noop row is visible, not noise.
            var noopRows = hub.ledger(BlotterQuery.parse("role=noop"));
            assertEquals(1, noopRows.total());
            assertEquals("fee", noopRows.rows().getFirst().externalId());
            assertEquals("noop", noopRows.rows().getFirst().role());
            assertEquals(2, hub.ledger(BlotterQuery.parse("role=transaction")).total());
        }
    }

    @Test
    void aMarkNoopDecisionIsNamedOverTheProfile(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir, false);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "a", -100, 900, "Opening spend"),
                fact(2, "b", 500, 1400, "Deposit"),
                fact(3, "fee", -29900, 0, "Orange Advantage annual fee"),
                new Decision.MarkNoop(4, "fee", "decided by a person", Actor.USER, "ron", AT)));
        }

        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 3L
                && hub.status().counts().getOrDefault("decision", 0L) == 1L);
            ReconcileResponse r = hub.reconcile();
            assertTrue(r.ok());
            ReconcileResponse.AccountJson acct = r.accounts().stream()
                .filter(a -> a.accountRef().equals("ing-savings")).findFirst().orElseThrow();
            assertEquals(1, acct.exclusions().size());
            ReconcileResponse.ExclusionJson ex = acct.exclusions().getFirst();
            assertEquals("fee", ex.externalId());
            assertEquals("decision 4", ex.classifiedBy());
            assertEquals("decided by a person", ex.reason());
        }
    }

    @Test
    void chainsExposeForksAndPerSidePreviews(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir, false);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "a", -100, 900, "Opening spend"),
                fact(2, "b", 300, 1200, "Deposit"),
                fact(3, "c", 50, 950, "Extra movement")));
        }

        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 3L);
            ChainsResponse chains = hub.chains("ing-savings");
            assertEquals(1, chains.accounts().size());
            ChainsResponse.AccountJson acct = chains.accounts().getFirst();
            assertEquals("broken", acct.status());
            assertEquals(1, acct.forks().size());
            assertEquals("OPENING", acct.forks().getFirst().side());
            assertEquals(900, acct.forks().getFirst().value());
            assertEquals(List.of("b", "c"), acct.forks().getFirst().externalIds());

            // Each contesting row is previewed: nooping c closes the chain, and it says so.
            assertEquals(2, acct.previews().size());
            ChainsResponse.PreviewJson noopC = acct.previews().stream()
                .filter(p -> p.externalId().equals("c")).findFirst().orElseThrow();
            assertTrue(noopC.reconciled());
            assertEquals(1000, noopC.opening());
            assertEquals(1200, noopC.closing());
        }
    }

    private static Fact fact(long n, String id, long amount, long balance, String raw) {
        return new Fact(n, id, "ing-savings", LocalDate.of(2026, 9, (int) n), amount, balance, raw, null, 0,
            Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", AT);
    }

    private static void config(Path configDir, boolean withProfile) throws Exception {
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
                currency: "AUD"
                balanceSource: statement
            """);
        Files.writeString(configDir.resolve("users.yaml"), """
            users:
              - id: "ron"
                name: "Ron"
                active: true
            """);
        Files.writeString(configDir.resolve("categories.yaml"), """
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when:
                  match: "Opening"
            """);
        Files.writeString(configDir.resolve("transfers.yaml"), """
            windowDays: 4
            allowlist:
              - 'Transfer'
            """);
        if (withProfile) {
            Files.writeString(configDir.resolve("profiles.yaml"), """
                profiles:
                  ing-savings:
                    rules:
                      - match: 'Orange Advantage annual fee'
                        action: MARK_NOOP
                        reason: 'a statement snapshot, not a posting'
                """);
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within 10s");
    }
}
