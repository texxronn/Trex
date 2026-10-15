package trex.v2.egress.ezb;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ezbookkeeping API client (V2-SPEC.md §11.2): timezone headers, retries, read routes. */
class EzbClientTest {

    private static final ZoneId SYD = ZoneId.of("Australia/Sydney");

    @Test
    void everyWriteCarriesTheTimezoneHeaders() throws Exception {
        try (FakeEzb fake = new FakeEzb()) {
            new EzbClient(fake.url(), "token", SYD).addAccount("ING Savings", 8, "AUD");

            assertEquals(SYD.getId(), fake.lastWriteHeaders.get("x-timezone-name"));
            int offset = Integer.parseInt(fake.lastWriteHeaders.get("x-timezone-offset"));
            assertTrue(offset == 600 || offset == 660, "Sydney is +10:00 or +11:00, not " + offset);
            assertEquals("Bearer token", fake.lastWriteHeaders.get("authorization"));
        }
    }

    @Test
    void everyReadCarriesTheTimezoneHeadersToo() throws Exception {
        try (FakeEzb fake = new FakeEzb()) {
            EzbClient client = new EzbClient(fake.url(), "token", SYD);
            client.transactionIds();          // GET transactions/list/all.json
            assertEquals(SYD.getId(), fake.lastHeaders.get("x-timezone-name"),
                "the instance refuses a read without the timezone headers");
            assertNotNull(fake.lastHeaders.get("x-timezone-offset"));
        }
    }

    @Test
    void theFastClearMintsASessionTokenAndClearsInOneCall() throws Exception {
        try (FakeEzb fake = new FakeEzb()) {
            fake.seedAccount("1", "ING Savings", 8, "AUD");
            fake.drift("1", 1234);            // one transaction to clear
            EzbClient client = new EzbClient(fake.url(), "token", SYD);

            client.clearAll(new EzbClient.Credentials("ron", "secret"));

            assertEquals(0, client.transactionIds().size(), "cleared");
            assertEquals(1, fake.authorizeCalls.get());
            assertEquals(1, fake.dataClearCalls.get());
            assertEquals(0, fake.deletes.get(), "the per-id delete is never used in fast mode");
        }
    }

    @Test
    void aWrongPasswordFailsTheFastClearBeforeItDeletes() throws Exception {
        try (FakeEzb fake = new FakeEzb()) {
            fake.seedAccount("1", "ING Savings", 8, "AUD");
            fake.drift("1", 1234);
            EzbClient client = new EzbClient(fake.url(), "token", SYD);

            assertThrows(IllegalStateException.class,
                () -> client.clearAll(new EzbClient.Credentials("ron", "wrong")));
            assertEquals(1, client.transactionIds().size(), "nothing was cleared");
            assertEquals(0, fake.dataClearCalls.get());
        }
    }

    @Test
    void aTransientFailureIsRetried() throws Exception {
        try (FakeEzb fake = new FakeEzb()) {
            fake.forcedStatuses.add(500);
            EzbClient client = new EzbClient(fake.url(), "token", SYD, new EzbClient.Retry(2, 1, 2));
            assertNotNull(client.addAccount("X", 2, "AUD"));
            assertEquals(2, fake.attempts.get(), "one failed attempt, one succeeded");
        }
    }

    @Test
    void aRateLimitIsRetriedToo() throws Exception {
        try (FakeEzb fake = new FakeEzb()) {
            fake.forcedStatuses.add(429);
            EzbClient client = new EzbClient(fake.url(), "token", SYD, new EzbClient.Retry(3, 1, 2));
            assertNotNull(client.addAccount("X", 2, "AUD"));
            assertEquals(2, fake.attempts.get());
        }
    }

    @Test
    void aFourHundredIsNeverRetriedAndNamesTheRouteAndBody() throws Exception {
        try (FakeEzb fake = new FakeEzb()) {
            fake.forcedStatuses.add(400);
            EzbClient client = new EzbClient(fake.url(), "token", SYD, new EzbClient.Retry(5, 1, 2));
            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.addAccount("X", 2, "AUD"));
            assertTrue(e.getMessage().contains("accounts/add.json"), e.getMessage());
            assertTrue(e.getMessage().contains("forced"), e.getMessage());
            assertEquals(1, fake.attempts.get(), "a 4xx is terminal, not retried");
        }
    }

    @Test
    void importIdleReadsTheProcessRoute() throws Exception {
        try (FakeEzb fake = new FakeEzb()) {
            EzbClient client = new EzbClient(fake.url(), "token", SYD);
            assertTrue(client.importIdle());
            fake.importInProgress = true;
            assertFalse(client.importIdle(), "the process route says an import is running");
            assertEquals(2, fake.importProcessGets.get());
        }
    }

    @Test
    void aTrailingSlashOnTheBaseUrlIsNormalised() throws Exception {
        try (FakeEzb fake = new FakeEzb()) {
            EzbClient client = new EzbClient(fake.url() + "/", "token", SYD);
            assertNotNull(client.addAccount("X", 2, "AUD"));
        }
    }

    @Test
    void accountsAndCategoriesAndTransactionIdsAreReadBack() throws Exception {
        try (FakeEzb fake = new FakeEzb()) {
            EzbClient client = new EzbClient(fake.url(), "token", SYD);
            fake.seedAccount("1", "ING Savings", 8, "AUD");
            fake.seedCategory("p1", "trex-expense", 2, "0");
            fake.seedCategory("c1", "GROCERIES", 2, "p1");

            assertEquals(1, client.accountsByName().size());
            assertNotEquals(0, client.categories().stream()
                .filter(c -> c.name().equals("GROCERIES")).count());
            assertEquals(0, client.transactionIds().size());
        }
    }
}
