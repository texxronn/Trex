package trex.ingest.ing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.Candidate;
import trex.core.CandidateResult;
import trex.ingest.IngestRunner;
import trex.ingest.TrexClient;
import trex.sequencer.http.HttpApi;
import trex.sequencer.ingest.Account;
import trex.sequencer.ingest.AccountRegistry;
import trex.sequencer.ingest.Sequencer;
import trex.sequencer.ingest.TransferRules;
import trex.sequencer.journal.JsonlJournal;
import trex.sequencer.journal.Recovery;
import trex.sequencer.state.Fold;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC §4 acceptance and §7 test 13 against an in-process sequencer. */
class EndToEndTest {

    @TempDir
    Path dir;

    private final List<AutoCloseable> open = new ArrayList<>();

    private URI startServer(Path journalPath) {
        Recovery.recover(journalPath, journalPath);
        JsonlJournal journal = new JsonlJournal(journalPath);
        Sequencer sequencer = new Sequencer(journal, Fold.fold(journal),
            new AccountRegistry(List.of(new Account("ing-savings", "AUD"))),
            new TransferRules(List.of("Fast Transfer", "Internal Transfer"), 3),
            Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC));
        HttpApi api = new HttpApi(sequencer, 0, HttpApi.DEFAULT_MAX_BODY_BYTES).start();
        open.add(api);
        open.add(journal);
        return URI.create("http://127.0.0.1:" + api.port());
    }

    @AfterEach
    void stop() throws Exception {
        for (AutoCloseable c : open) {
            c.close();
        }
    }

    /** 20 days, several rows per day, identical-signature rows within days, balance chain intact. */
    private Path largeStatement() throws IOException {
        StringBuilder csv = new StringBuilder("Date,Description,Credit,Debit,Balance\n");
        long balance = 1_000_000;
        LocalDate day = LocalDate.parse("2026-05-01");
        for (int d = 0; d < 20; d++) {
            String date = "%02d/%02d/%d".formatted(day.getDayOfMonth(), day.getMonthValue(), day.getYear());
            for (int k = 0; k < 3; k++) {
                balance -= 450;
                csv.append(date).append(",\"COFFEE CART, SYDNEY\",,-4.50,").append(cents(balance)).append('\n');
            }
            balance += 12_345;
            csv.append(date).append(",Refund - Receipt ").append(1000 + d).append(",123.45,,").append(cents(balance)).append('\n');
            if (d % 5 == 0) {
                balance -= 20_000;
                csv.append(date).append(",Fast Transfer to CBA,,-200.00,").append(cents(balance)).append('\n');
            }
            day = day.plusDays(1);
        }
        Path file = dir.resolve("large.csv");
        Files.writeString(file, csv);
        return file;
    }

    private static String cents(long c) {
        return java.math.BigDecimal.valueOf(c, 2).toPlainString();
    }

    private static int run(URI url, Path file, int batchRows, boolean gzip, ByteArrayOutputStream out) {
        return IngestRunner.run(IngCsv.SOURCE_TYPE, "ing-savings", url, file, batchRows, gzip, new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    @Test
    void rerunIsAllDroppedDuplicate() throws Exception {
        Path journal = dir.resolve("j.jsonl");
        URI url = startServer(journal);
        Path file = IngCsvTest.resource("ing-slice.csv");
        assertEquals(IngestRunner.OK, run(url, file, Integer.MAX_VALUE, true, new ByteArrayOutputStream()));
        long size = Files.size(journal);

        List<Candidate> rows = IngCsv.parse(file, "ing-savings").candidates();
        TrexClient.Response again = new TrexClient(url, true).post(rows);
        again.results().forEach(r -> assertInstanceOf(CandidateResult.DroppedDuplicate.class, r));
        assertEquals(size, Files.size(journal));
    }

    @Test
    void gzippedAndPlainRunsProduceIdenticalJournals() throws Exception {
        Path plain = dir.resolve("plain.jsonl");
        Path gz = dir.resolve("gzip.jsonl");
        Path file = largeStatement();
        assertEquals(IngestRunner.OK, run(startServer(plain), file, Integer.MAX_VALUE, false, new ByteArrayOutputStream()));
        assertEquals(IngestRunner.OK, run(startServer(gz), file, Integer.MAX_VALUE, true, new ByteArrayOutputStream()));
        assertArrayEquals(Files.readAllBytes(plain), Files.readAllBytes(gz));
    }

    @Test
    void dayAtomicSplitYieldsByteIdenticalJournal() throws Exception {
        Path single = dir.resolve("single.jsonl");
        Path split = dir.resolve("split.jsonl");
        Path file = largeStatement();
        assertEquals(IngestRunner.OK, run(startServer(single), file, Integer.MAX_VALUE, true, new ByteArrayOutputStream()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(IngestRunner.OK, run(startServer(split), file, 9, true, out));
        assertTrue(out.toString(StandardCharsets.UTF_8).lines().filter(l -> l.startsWith("batch ")).count() > 5);
        assertTrue(Files.size(single) > 0);
        assertArrayEquals(Files.readAllBytes(single), Files.readAllBytes(split));
    }

    @Test
    void midDaySplitMisNumbersOcc() throws Exception {
        Path single = dir.resolve("single.jsonl");
        Path midDay = dir.resolve("midday.jsonl");
        Path file = largeStatement();
        List<Candidate> rows = IngCsv.parse(file, "ing-savings").candidates();

        new TrexClient(startServer(single), true).post(rows);

        // Split between the 1st and 2nd identical COFFEE CART rows of the first day.
        TrexClient bad = new TrexClient(startServer(midDay), true);
        bad.post(rows.subList(0, 1));
        TrexClient.Response second = bad.post(rows.subList(1, rows.size()));

        assertInstanceOf(CandidateResult.Flagged.class, second.results().getFirst(),
            "second identical row restarts at occ=0 and collides with the first");
        assertFalse(Files.readString(midDay).isEmpty());
        assertNotEquals(Files.readString(single), Files.readString(midDay));
    }

    @Test
    void invalidFileSendsNothing() throws Exception {
        Path journal = dir.resolve("j.jsonl");
        URI url = startServer(journal);
        Path file = dir.resolve("bad.csv");
        Files.writeString(file, "Date,Description,Credit,Debit,Balance\n01/06/2026,ok,,-1.00,9.00\n02/06/2026,bad,,-1.001,8.00\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(IngestRunner.INVALID_FILE, run(url, file, Integer.MAX_VALUE, true, out));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("bad.csv:3 column Debit value '-1.001'"));
        assertEquals(0, Files.size(journal));
    }
}
