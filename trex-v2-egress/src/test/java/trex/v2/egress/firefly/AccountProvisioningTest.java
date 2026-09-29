package trex.v2.egress.firefly;

import org.junit.jupiter.api.Test;
import trex.v2.egress.hub.HubClient.OpeningState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §15.21: a liability is seeded negative, an asset positive, and only missing accounts are created. */
class AccountProvisioningTest {

    @Test
    void aLiabilityIsSeededNegative() {
        assertEquals(-1000, AccountProvisioning.seedOpening(AccountMap.Kind.LIABILITY, 1000));
        assertEquals(-1000, AccountProvisioning.seedOpening(AccountMap.Kind.LIABILITY, -1000));
        assertEquals(1000, AccountProvisioning.seedOpening(AccountMap.Kind.ASSET, 1000));
    }

    @Test
    void createsOnlyTheMissingAccountsUsingTheBackwardOpening() throws Exception {
        try (FakeFirefly fake = new FakeFirefly()) {
            Path file = Files.createTempFile("firefly", ".yaml");
            Files.writeString(file, """
                accounts:
                  ing-savings: { name: "ING Savings", type: asset }
                  ing-card:    { name: "ING Card",    type: liability }
                """);
            AccountMap map = AccountMap.load(file);
            AccountMap resolved = map.resolved(Map.of("ING Savings", "1"));   // card is missing

            Map<String, OpeningState> openings = Map.of("ing-card",
                new OpeningState("ing-card", "AUD", false, 0, 12345, 0, 0, java.time.LocalDate.of(2026, 9, 1)));
            List<String> created = AccountProvisioning.createMissing(
                new FireflyClient(fake.url(), "token"), map, resolved, openings);

            assertEquals(List.of("ing-card"), created);
            assertEquals(1, fake.createdAccounts.size());
            Map<String, Object> body = fake.createdAccounts.getFirst();
            assertEquals("liability", body.get("type"));
            assertEquals("debt", body.get("liability_type"));
            assertEquals("credit", body.get("liability_direction"));
            assertEquals("-123.45", body.get("opening_balance"), "a liability opens negative");
            assertEquals("2026-09-01", body.get("opening_balance_date"));
            assertTrue(fake.accounts.containsKey("ING Card"));
        }
    }
}
