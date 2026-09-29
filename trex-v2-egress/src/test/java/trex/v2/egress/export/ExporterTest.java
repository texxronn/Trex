package trex.v2.egress.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.egress.hub.HubClient.LedgerRow;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The export formats (V2-PROPOSAL.md §1): the living truth, portable. */
class ExporterTest {

    private static final List<LedgerRow> ROWS = List.of(new LedgerRow("id1", 1, "ing-savings",
        LocalDate.of(2026, 9, 1), -1000, 900, "COLES, 1234", "EXTERNAL", null, "GROCERIES", "RULE",
        "rule #1", false));

    private static final Map<String, String> CURRENCY = Map.of("ing-savings", "AUD");

    @Test
    void csvQuotesFieldsWithCommas() {
        String csv = Exporter.csv(ROWS, CURRENCY);
        assertTrue(csv.startsWith("n,date,account,currency,amount_cents"), csv);
        assertTrue(csv.contains("\"COLES, 1234\""), "a comma inside a field is quoted");
        assertTrue(csv.contains(",AUD,"), "currency joined from refdata");
    }

    @Test
    void jsonIsTheRows() {
        String json = Exporter.json(ROWS);
        assertTrue(json.contains("\"category\" : \"GROCERIES\""), json);
        assertTrue(json.contains("\"externalId\" : \"id1\""));
    }

    @Test
    void sqliteWritesAFreshFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("export.sqlite");
        Exporter.sqlite(file, ROWS, CURRENCY);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ledger")) {
                assertEquals(1, rs.next() ? rs.getLong(1) : 0);
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT currency, amount_cents FROM ledger WHERE external_id = 'id1'")) {
                assertTrue(rs.next());
                assertEquals("AUD", rs.getString(1));
                assertEquals(-1000, rs.getLong(2));
            }
        }
    }
}
