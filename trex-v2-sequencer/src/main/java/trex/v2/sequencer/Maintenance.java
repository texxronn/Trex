package trex.v2.sequencer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * The sequencer's maintenance operations (V2-PROPOSAL.md §5.5, §12.6). Today: a **journal snapshot**
 * — a consistent gzip **copy** of the log prefix up to the last fsynced record, written to the
 * archive. It is never a rotation; the live journal is untouched. The copy is read outside the
 * append lock (the prefix {@code [0, offset)} is fixed), so a large copy never stalls the writer.
 */
final class Maintenance implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Maintenance.class);
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    record Snapshot(long n, long offset, long bytes, String path) {}

    private final Path journal;
    private final Path archive;   // nullable: no archive configured
    private final Sequencer sequencer;
    private final ExecutorService background =
        Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("trex-snapshot").factory());

    Maintenance(Path journal, Path archive, Sequencer sequencer) {
        this.journal = journal;
        this.archive = archive;
        this.sequencer = sequencer;
    }

    boolean configured() {
        return archive != null;
    }

    /** Take one snapshot now, blocking until the gzip is written. */
    synchronized Snapshot snapshot() throws IOException {
        if (archive == null) {
            throw new IllegalStateException("no archive configured (start the sequencer with --archive)");
        }
        long offset = sequencer.head().offset();
        long n = sequencer.head().n();
        Path dir = archive.resolve("journal");
        Files.createDirectories(dir);
        Path target = dir.resolve("trex-" + STAMP.format(Instant.now()) + ".jsonl.gz");
        try (InputStream in = Files.newInputStream(journal);
             OutputStream gz = new GZIPOutputStream(Files.newOutputStream(target))) {
            byte[] buffer = new byte[65536];
            long remaining = offset;
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read == -1) {
                    break;
                }
                gz.write(buffer, 0, read);
                remaining -= read;
            }
        }
        return new Snapshot(n, offset, Files.size(target), target.toString());
    }

    /** Async: return immediately; the copy happens on a background thread. */
    void snapshotAsync() {
        background.submit(() -> {
            try {
                snapshot();
            } catch (Exception e) {
                log.warn("background journal snapshot failed", e);
            }
        });
    }

    @Override
    public void close() {
        background.shutdownNow();
    }
}
