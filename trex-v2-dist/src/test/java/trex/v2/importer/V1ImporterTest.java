package trex.v2.importer;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Ids;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.User;
import trex.v2.log.Json;
import trex.v2.log.JsonlJournal;
import trex.v2.sequencer.Sequencer;
import trex.v2.sequencer.api.BatchResponse;
import trex.v2.sequencer.api.DecisionBatch;
import trex.v2.sequencer.api.FactBatch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The §16 mapping, exercised against a synthetic v1 journal. Identity is re-minted by the
 * sequencer and must match v1's; the automatic states re-derive while the decisions are imported.
 */
class V1ImporterTest {

    private static final Instant AT = Instant.parse("2026-09-29T08:00:00Z");

    @Test
    void distinctContentRowsOnADayKeepTheirV1Ids(@TempDir Path dir) throws Exception {
        // v1's occ is per identical content, so two different receipt-less rows on one day are each
        // occ 0. Importing them must not re-mint their ids.
        LocalDate day = LocalDate.of(2026, 9, 1);
        String first = Ids.contentHash("ing-savings", day, -1000, "COLES 1234", 0);
        String second = Ids.contentHash("ing-savings", day, -2000, "OTHER SHOP", 0);

        Path v1 = dir.resolve("v1.jsonl");
        List<ObjectNode> lines = List.of(
            line(1, first, "ing-savings", day, -1000, 900, "COLES 1234", "WITHDRAWAL", "EXTERNAL", List.of()),
            line(2, second, "ing-savings", day, -2000, 700, "OTHER SHOP", "WITHDRAWAL", "EXTERNAL", List.of()));
        StringBuilder jsonl = new StringBuilder();
        for (ObjectNode line : lines) {
            jsonl.append(Json.mapper().writeValueAsString(line)).append('\n');
        }
        Files.writeString(v1, jsonl.toString());

        try (Sequencer sequencer = new Sequencer(new JsonlJournal(dir.resolve("v2.jsonl")),
                registry(), rules(), Clock.fixed(AT, ZoneOffset.UTC))) {
            V1Importer.Report report = V1Importer.importJournal(v1, null, new DirectSink(sequencer));
            assertTrue(report.identityMismatches().isEmpty(), report.identityMismatches().toString());
            assertEquals(2, report.facts());
        }
    }

    @Test
    void mapsAV1JournalWithoutLosingIdentity(@TempDir Path dir) throws Exception {
        LocalDate day = LocalDate.of(2026, 9, 1);
        // occ is per identical content (v1's rule): distinct rows on a day are each occ 0.
        String legA = Ids.contentHash("ing-savings", day, -1000, "COLES 1234", 0);
        String legB = Ids.contentHash("ing-orange", day, 1000, "Transfer to Savings 4321", 0);
        String external = Ids.contentHash("ing-savings", day, -2000, "PAYPAL THING", 0);
        String dup = Ids.contentHash("ing-savings", day, -3000, "COLES 9999", 0);

        Path v1 = dir.resolve("v1.jsonl");
        List<ObjectNode> lines = List.of(
            line(1, legA, "ing-savings", day, -1000, 0, "COLES 1234", "WITHDRAWAL", "HELD", List.of()),
            line(2, legB, "ing-orange", day, 1000, 0, "Transfer to Savings 4321", "DEPOSIT", "HELD", List.of()),
            transfer(3, legA, legB, "internal"),
            line(4, legA, "ing-savings", day, -1000, 0, "COLES 1234", "WITHDRAWAL", "MATCHED", List.of()),
            line(5, legB, "ing-orange", day, 1000, 0, "Transfer to Savings 4321", "DEPOSIT", "MATCHED", List.of()),
            line(6, external, "ing-savings", day, -2000, 0, "PAYPAL THING", "WITHDRAWAL", "EXTERNAL", List.of()),
            withComment(line(7, external, "ing-savings", day, -2000, 0, "PAYPAL THING", "WITHDRAWAL", "EXTERNAL",
                List.of()), "ordinary payment"),
            line(8, dup, "ing-savings", day, -3000, 0, "COLES 9999", "WITHDRAWAL", "REVIEW",
                List.of("POTENTIAL_DUP")),
            withComment(line(9, dup, "ing-savings", day, -3000, 0, "COLES 9999", "WITHDRAWAL", "REVIEW",
                List.of()), "not a duplicate"));
        StringBuilder jsonl = new StringBuilder();
        for (ObjectNode line : lines) {
            jsonl.append(Json.mapper().writeValueAsString(line)).append('\n');
        }
        Files.writeString(v1, jsonl.toString());

        Path pins = dir.resolve("pins.yaml");
        Files.writeString(pins, """
            pins:
              - category: GROCERIES
                comment: "the one-off"
                when:
                  externalId: ["%s"]
            """.formatted(legA));

        try (Sequencer sequencer = new Sequencer(new JsonlJournal(dir.resolve("v2.jsonl")),
                registry(), rules(), Clock.fixed(AT, ZoneOffset.UTC))) {
            V1Importer.Report report = V1Importer.importJournal(v1, pins, new DirectSink(sequencer));
            assertTrue(report.identityMismatches().isEmpty(), "identity must be preserved: " + report.identityMismatches());
            assertEquals(0, report.identityMismatches().size());
            assertEquals(4, report.facts(), "the first observation of each id is the fact");
            assertEquals(1, report.pairs());
            assertEquals(1, report.markExternal());
            assertEquals(1, report.dismiss());
            assertEquals(1, report.pins());
            assertEquals(8, sequencer.headN(), "4 facts + PAIR + MARK_EXTERNAL + DISMISS + PIN");
        }
    }

    // ---- synthetic v1 lines -----------------------------------------------------------------

    private static ObjectNode line(long n, String id, String account, LocalDate date, long amount, long balance,
                                   String raw, String typeHint, String state, List<String> flags) {
        ObjectNode o = Json.mapper().createObjectNode();
        o.put("n", n);
        o.put("externalId", id);
        o.put("accountRef", account);
        o.put("toAccountRef", (String) null);
        o.put("currency", "AUD");
        o.put("date", date.toString());
        o.put("amount", amount);
        o.put("balance", balance);
        o.put("description", raw);
        o.put("rawDescription", raw);
        o.put("typeHint", typeHint);
        o.put("transferKey", (String) null);
        o.putNull("legIds");
        o.putNull("corrects");
        o.put("state", state);
        o.putNull("confidence");
        ArrayNode f = o.putArray("flags");
        flags.forEach(f::add);
        o.put("provenance", "BANK");
        o.put("sourceType", "ing-csv");
        o.putNull("receipt");
        o.putNull("counterpartyBsb");
        o.putNull("counterpartyAcct");
        o.putNull("foreignAmount");
        o.putNull("foreignCurrency");
        o.putNull("comment");
        o.put("ingestedAt", AT.toString());
        return o;
    }

    private static ObjectNode withComment(ObjectNode o, String comment) {
        o.put("comment", comment);
        return o;
    }

    private static ObjectNode transfer(long n, String legA, String legB, String comment) {
        ObjectNode o = line(n, "TRF-x", "ing-savings", LocalDate.of(2026, 9, 1), 0, 0, "transfer",
            "TRANSFER", "MATCHED", List.of());
        ArrayNode legs = o.putArray("legIds");
        legs.add(legA);
        legs.add(legB);
        o.put("comment", comment);
        return o;
    }

    private static Registry registry() {
        Map<String, Account> accounts = Map.of(
            "ing-savings", new Account("ing-savings", "AUD", BalanceSource.STATEMENT, 7),
            "ing-orange", new Account("ing-orange", "AUD", BalanceSource.STATEMENT, 7));
        return new Registry(accounts, Map.of("ron", new User("ron", "Ron", true, "weekly")));
    }

    private static RuleSet rules() {
        return RuleSet.compile("t.yaml", new RuleSet.File(List.of("GROCERIES"), List.of()));
    }

    private record DirectSink(Sequencer sequencer) implements V1Importer.Sink {
        @Override
        public BatchResponse facts(FactBatch batch) {
            return sequencer.submitFacts(batch);
        }

        @Override
        public BatchResponse decisions(DecisionBatch batch) {
            return sequencer.submitDecisions(batch);
        }
    }
}
