package trex.v2.egress.firefly;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountChecksTest {

    private static AccountMap map() throws Exception {
        Path file = Files.createTempFile("firefly", ".yaml");
        Files.writeString(file, """
            accounts:
              ing-savings:    { name: "ING Savings",     type: asset }
              ing-credit-card: { name: "ING Credit Card", type: liability }
            """);
        return AccountMap.load(file).resolved(Map.of("ING Savings", "1", "ING Credit Card", "2"));
    }

    @Test
    void agreementIsSilent() throws Exception {
        var instance = Map.of(
            "ING Savings", new FireflyClient.AccountInfo("1", "ING Savings", "asset", "AUD", "0"),
            "ING Credit Card", new FireflyClient.AccountInfo("2", "ING Credit Card", "liability", "AUD", "0"));
        assertTrue(AccountChecks.problems(map(), instance,
            Map.of("ing-savings", "AUD", "ing-credit-card", "AUD")).isEmpty());
    }

    @Test
    void aWrongTypeAndAWrongCurrencyAreBothNamed() throws Exception {
        var instance = Map.of(
            "ING Savings", new FireflyClient.AccountInfo("1", "ING Savings", "asset", "USD", "0"),
            "ING Credit Card", new FireflyClient.AccountInfo("2", "ING Credit Card", "asset", "AUD", "0"));
        List<String> problems = AccountChecks.problems(map(), instance,
            Map.of("ing-savings", "AUD", "ing-credit-card", "AUD"));
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("ing-credit-card") && problems.get(0).contains("liability"));
        assertTrue(problems.get(1).contains("ing-savings") && problems.get(1).contains("USD"));
    }
}
