package trex.v2.egress.export;

import trex.v2.egress.hub.HubClient.LedgerRow;
import trex.v2.log.Json;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * Export the derived ledger so the living truth is never trapped (V2-PROPOSAL.md §1, the crown
 * checklist #10). It writes a new file from the hub's API; the live index is never shared.
 */
public final class Exporter {

    private Exporter() {}

    private static final String[] COLUMNS = {
        "n", "date", "account", "currency", "amount_cents", "balance_cents", "category", "origin",
        "leg", "transfer_id", "external_id", "raw_description"
    };

    public static String csv(List<LedgerRow> rows, Map<String, String> currencyByAccount) {
        StringBuilder out = new StringBuilder();
        out.append(String.join(",", COLUMNS)).append('\n');
        for (LedgerRow row : rows) {
            out.append(row.n()).append(',')
               .append(row.date()).append(',')
               .append(csv(row.accountRef())).append(',')
               .append(csv(currencyByAccount.getOrDefault(row.accountRef(), ""))).append(',')
               .append(row.amount()).append(',')
               .append(row.balance()).append(',')
               .append(csv(row.category())).append(',')
               .append(csv(row.categoryOrigin())).append(',')
               .append(csv(row.leg())).append(',')
               .append(csv(row.transferId() == null ? "" : row.transferId())).append(',')
               .append(row.externalId()).append(',')
               .append(csv(row.rawDescription())).append('\n');
        }
        return out.toString();
    }

    public static String json(List<LedgerRow> rows) {
        try {
            return Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(rows);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot serialise the export", e);
        }
    }

    /** A fresh SQLite file with a {@code transaction} table; never the live index. */
    public static void sqlite(Path file, List<LedgerRow> rows, Map<String, String> currencyByAccount) {
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url)) {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS ledger (
                      n INTEGER PRIMARY KEY, date TEXT NOT NULL, account TEXT NOT NULL,
                      currency TEXT, amount_cents INTEGER NOT NULL, balance_cents INTEGER NOT NULL,
                      category TEXT, origin TEXT, leg TEXT, transfer_id TEXT,
                      external_id TEXT NOT NULL, raw_description TEXT NOT NULL)""");
            }
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ledger(n, date, account, currency, amount_cents, balance_cents, "
                        + "category, origin, leg, transfer_id, external_id, raw_description) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (LedgerRow row : rows) {
                    ps.setLong(1, row.n());
                    ps.setString(2, row.date().toString());
                    ps.setString(3, row.accountRef());
                    ps.setString(4, currencyByAccount.get(row.accountRef()));
                    ps.setLong(5, row.amount());
                    ps.setLong(6, row.balance());
                    ps.setString(7, row.category());
                    ps.setString(8, row.categoryOrigin());
                    ps.setString(9, row.leg());
                    ps.setString(10, row.transferId());
                    ps.setString(11, row.externalId());
                    ps.setString(12, row.rawDescription());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot write " + file, e);
        }
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
