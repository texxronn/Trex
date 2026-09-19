package trex.egress.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import trex.core.CanonicalEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.journal.FramedReader;
import trex.journal.JournalChanges;
import trex.journal.JournalPresence;
import trex.journal.Json;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.function.IntConsumer;

/**
 * Log-mirror follower into SQLite (WAL): one row per journal line keyed by n (SPEC §5.3).
 * Inserts and the follower_state offset commit in one transaction, so re-delivery is a no-op.
 */
public final class SqliteFollower implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqliteFollower.class);

    static final String SCHEMA = """
        CREATE TABLE IF NOT EXISTS journal (
          n INTEGER PRIMARY KEY,
          external_id TEXT NOT NULL,
          account_ref TEXT, to_account_ref TEXT, currency TEXT,
          date TEXT, amount INTEGER, balance INTEGER,
          description TEXT, raw_description TEXT,
          type_hint TEXT, transfer_key TEXT, leg_ids TEXT,
          corrects TEXT, state TEXT, confidence TEXT, flags TEXT,
          provenance TEXT, source TEXT, receipt TEXT,
          counterparty_bsb TEXT, counterparty_acct TEXT,
          foreign_amount INTEGER, foreign_currency TEXT,
          comment TEXT, ingested_at TEXT
        );
        CREATE INDEX IF NOT EXISTS journal_external_id ON journal(external_id);
        CREATE TABLE IF NOT EXISTS follower_state (k TEXT PRIMARY KEY, offset INTEGER);
        """;

    private static final String INSERT = """
        INSERT INTO journal (n, external_id, account_ref, to_account_ref, currency, date, amount, balance,
          description, raw_description, type_hint, transfer_key, leg_ids, corrects, state, confidence, flags,
          provenance, source, receipt, counterparty_bsb, counterparty_acct, foreign_amount, foreign_currency,
          comment, ingested_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(n) DO NOTHING
        """;

    private static final String CURSOR_KEY = "journal";
    private static final int LINES_PER_TRANSACTION = 5_000;

    private final Path journal;
    private final Connection db;
    private final JournalPresence presence;

    public SqliteFollower(Path journal, Path database) throws SQLException {
        this.journal = journal;
        this.presence = new JournalPresence(journal);
        this.db = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            for (String ddl : SCHEMA.split(";")) {
                if (!ddl.isBlank()) {
                    st.execute(ddl);
                }
            }
        }
        db.setAutoCommit(false);
        log.info("sqlite mirror ready at {} (WAL)", database);
    }

    /**
     * Follow until interrupted: a pass now, then a pass on every journal change event, or after
     * {@code fallbackMillis} without one. {@code onPass} receives each pass's consumed-line count.
     */
    public void follow(long fallbackMillis, IntConsumer onPass) throws SQLException, InterruptedException {
        log.info("sqlite follower started: journal {} (fallback {} ms)", journal, fallbackMillis);
        try (JournalChanges changes = new JournalChanges(journal)) {
            while (!Thread.currentThread().isInterrupted()) {
                onPass.accept(pass());
                changes.await(fallbackMillis);
            }
        }
    }

    /** One follower pass. Returns the number of journal lines consumed; 0 if there is no journal yet. */
    public int pass() throws SQLException {
        if (!presence.ready()) {
            return 0;       // SPEC §5.1: no journal, no pass; the cursor stays where it is
        }
        long offset = readOffset();
        int consumed = 0;
        int inTransaction = 0;
        try (FramedReader tail = new FramedReader(journal, offset);
             PreparedStatement insert = db.prepareStatement(INSERT)) {
            FramedReader.Framed line;
            long advanced = offset;
            while ((line = tail.next()) != null) {
                bind(insert, line.event());
                insert.executeUpdate();
                advanced = line.endOffset();
                consumed++;
                if (++inTransaction == LINES_PER_TRANSACTION) {
                    commit(advanced);
                    inTransaction = 0;
                }
            }
            if (inTransaction > 0) {
                commit(advanced);
            }
        } catch (SQLException | RuntimeException e) {
            log.error("sqlite pass failed after {} lines from offset {}; rolling back", consumed, offset, e);
            db.rollback();
            throw e;
        }
        if (consumed > 0) {
            log.debug("mirrored {} lines into sqlite", consumed);
        }
        return consumed;
    }

    private void commit(long offset) throws SQLException {
        try (PreparedStatement st = db.prepareStatement(
            "INSERT INTO follower_state (k, offset) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET offset = excluded.offset")) {
            st.setString(1, CURSOR_KEY);
            st.setLong(2, offset);
            st.executeUpdate();
        }
        db.commit();
    }

    long readOffset() throws SQLException {
        try (PreparedStatement st = db.prepareStatement("SELECT offset FROM follower_state WHERE k = ?")) {
            st.setString(1, CURSOR_KEY);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } finally {
            db.commit();
        }
    }

    private static void bind(PreparedStatement st, CanonicalEvent e) throws SQLException {
        int i = 1;
        st.setLong(i++, e.n());
        st.setString(i++, e.externalId());
        st.setString(i++, e.accountRef());
        st.setString(i++, e.toAccountRef());
        st.setString(i++, e.currency());
        st.setString(i++, e.date() == null ? null : e.date().toString());
        st.setLong(i++, e.amount());
        st.setLong(i++, e.balance());
        st.setString(i++, e.description());
        st.setString(i++, e.rawDescription());
        st.setString(i++, e.typeHint().name());
        st.setString(i++, e.transferKey());
        st.setString(i++, e.legIds() == null ? null : json(e.legIds()));
        st.setString(i++, e.corrects());
        st.setString(i++, e.state().name());
        st.setString(i++, e.confidence() == null ? null : e.confidence().name());
        st.setString(i++, json(e.flags()));
        st.setString(i++, e.provenance() == null ? null : e.provenance().name());
        st.setString(i++, e.source());
        st.setString(i++, e.receipt());
        st.setString(i++, e.counterpartyBsb());
        st.setString(i++, e.counterpartyAcct());
        if (e.foreignAmount() == null) {
            st.setNull(i++, Types.INTEGER);
        } else {
            st.setLong(i++, e.foreignAmount());
        }
        st.setString(i++, e.foreignCurrency());
        st.setString(i++, e.comment());
        st.setString(i, e.ingestedAt() == null ? null : e.ingestedAt().toString());
    }

    private static String json(Object value) {
        try {
            return Json.mapper().writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public void close() throws SQLException {
        db.close();
    }
}
