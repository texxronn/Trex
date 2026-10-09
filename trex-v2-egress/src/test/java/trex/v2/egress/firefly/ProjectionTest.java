package trex.v2.egress.firefly;

import org.junit.jupiter.api.Test;
import trex.v2.egress.hub.HubClient.HubUnit;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** §15.19: the Firefly type follows the accounts, not the classification. */
class ProjectionTest {

    private static final String REV = "sha256:cfg";

    private static AccountMap accounts() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("firefly", ".yaml");
        java.nio.file.Files.writeString(file, """
            accounts:
              ing-savings:   { name: "ING Savings",   type: asset }
              ing-orange:    { name: "ING Orange",    type: asset }
              ing-card:      { name: "ING Card",      type: liability }
              ing-loan:      { name: "ING Loan",      type: liability }
            """);
        Map<String, String> ids = Map.of("ING Savings", "1", "ING Orange", "2", "ING Card", "3", "ING Loan", "4");
        return AccountMap.load(file).resolved(ids);
    }

    private static HubUnit unit(String kind, String from, String to, long amount) {
        return new HubUnit(kind.equals("TRANSFER") ? "TRF-x" : "ext", kind, 1, from, to,
            LocalDate.of(2026, 9, 1), amount, "AUD", "GROCERIES", "RULE", "EXTERNAL", false, false,
            "COLES 1234", "h", List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> split(Projection.Posting p) {
        return ((java.util.List<Map<String, Object>>) p.body().get("transactions")).getFirst();
    }

    @Test
    void theTypeMatrixFollowsTheAccounts() throws Exception {
        AccountMap a = accounts();
        assertEquals("transfer", split(Projection.of(unit("TRANSFER", "ing-savings", "ing-orange", -1000), REV, a))
            .get("type"), "asset -> asset");
        assertEquals("transfer", split(Projection.of(unit("TRANSFER", "ing-card", "ing-loan", -1000), REV, a))
            .get("type"), "liability -> liability");
        assertEquals("withdrawal", split(Projection.of(unit("TRANSFER", "ing-savings", "ing-card", -1000), REV, a))
            .get("type"), "asset -> liability");
        assertEquals("deposit", split(Projection.of(unit("TRANSFER", "ing-card", "ing-savings", 1000), REV, a))
            .get("type"), "liability -> asset");
    }

    @Test
    void anExternalSpendIsAWithdrawalToTheMerchantStemWithOurTags() throws Exception {
        Projection.Posting p = Projection.of(unit("EXTERNAL", "ing-savings", null, -1234), REV, accounts());
        Map<String, Object> s = split(p);
        assertEquals("withdrawal", s.get("type"));
        assertEquals("12.34", s.get("amount"));
        assertEquals("COLES 1234", s.get("destination_name"));
        assertEquals("GROCERIES", s.get("category_name"));
        assertEquals(java.util.List.of("trex", "trex-category:GROCERIES"), s.get("tags"));
        assertEquals(true, p.body().get("error_if_duplicate_hash"));
        assertFalse((Boolean) p.body().get("apply_rules"));
    }

    @Test
    void anIncomeIsADepositFromTheMerchantStem() throws Exception {
        HubUnit income = new HubUnit("ext2", "EXTERNAL", 2, "ing-savings", null, LocalDate.of(2026, 9, 2),
            250000, "AUD", "SALARY", "RULE", "EXTERNAL", false, false, "ACME SALARY", "h2", List.of());
        Map<String, Object> s = split(Projection.of(income, REV, accounts()));
        assertEquals("deposit", s.get("type"));
        assertEquals("2500.00", s.get("amount"));
        assertEquals("ACME SALARY", s.get("source_name"));
    }

    @Test
    void tagsKeepYoursAndReplaceOurs() {
        com.fasterxml.jackson.databind.JsonNode existing = trex.v2.log.Json.mapper().valueToTree(
            List.of("holiday", "trex", "trex-category:OLD", "tax-2026"));
        assertEquals(List.of("holiday", "tax-2026", "trex", "trex-category:NEW"),
            Projection.tags(existing, "NEW"));
    }
}
