package trex.v2.egress.firefly;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §15.20: a transient failure is retried, a 4xx is not. */
class FireflyClientTest {

    private static Map<String, Object> body() {
        Map<String, Object> split = new LinkedHashMap<>();
        split.put("external_id", "ext1");
        split.put("category_name", "GROCERIES");
        split.put("amount", "10.00");
        split.put("date", "2026-09-01");
        split.put("source_id", "1");
        split.put("destination_name", "COLES");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error_if_duplicate_hash", true);
        body.put("apply_rules", false);
        body.put("transactions", List.of(split));
        return body;
    }

    @Test
    void aTransientFailureIsRetried() throws Exception {
        try (FakeFirefly fake = new FakeFirefly()) {
            fake.forcedStatuses.add(500);
            FireflyClient client = new FireflyClient(fake.url(), "token", new FireflyClient.Retry(2, 1, 2));
            FireflyClient.Result result = client.post(body());
            assertTrue(result instanceof FireflyClient.Result.Created, result.toString());
            assertEquals(2, fake.attempts.get(), "one failed attempt, one succeeded");
        }
    }

    @Test
    void aFourHundredIsNeverRetried() throws Exception {
        try (FakeFirefly fake = new FakeFirefly()) {
            fake.forcedStatuses.add(400);
            FireflyClient client = new FireflyClient(fake.url(), "token", new FireflyClient.Retry(5, 1, 2));
            FireflyClient.Result result = client.post(body());
            assertTrue(result instanceof FireflyClient.Result.Failed, result.toString());
            assertEquals(400, ((FireflyClient.Result.Failed) result).status());
            assertEquals(1, fake.attempts.get(), "a 4xx is terminal, not retried");
        }
    }

    @Test
    void aDuplicateIsRecoveredFromTheRejection() throws Exception {
        try (FakeFirefly fake = new FakeFirefly()) {
            FireflyClient client = new FireflyClient(fake.url(), "token");
            client.post(body());
            FireflyClient.Result second = client.post(body());
            assertTrue(second instanceof FireflyClient.Result.Duplicate, second.toString());
            assertEquals("1", ((FireflyClient.Result.Duplicate) second).groupId());
        }
    }

    @Test
    void deletingAGroupThatIsAlreadyGoneIsNotAnError() throws Exception {
        try (FakeFirefly fake = new FakeFirefly()) {
            new FireflyClient(fake.url(), "token").deleteTransaction("404404");
            assertEquals(1, fake.deletes.get());
        }
    }
}
