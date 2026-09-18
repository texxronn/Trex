package trex.sequencer.journal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.journal.FramedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Startup recovery / materialize. SPEC §3.2. */
public final class Recovery {

    private static final Logger log = LoggerFactory.getLogger(Recovery.class);

    private Recovery() {}

    /**
     * Prepare {@code target} for append. When source and target differ, source is authoritative:
     * target is overwritten by a byte copy, verified by SHA-256. Any torn tail is truncated.
     *
     * @return the offset of the end of the last complete record (the journal head)
     */
    public static long recover(Path source, Path target) {
        try {
            if (sameFile(source, target)) {
                if (Files.notExists(target)) {
                    log.info("creating new journal {}", target);
                    Files.createFile(target);
                    syncFileAndDirectory(target);
                }
            } else {
                if (Files.notExists(source)) {
                    throw new IllegalStateException("journal.source does not exist: " + source);
                }
                log.info("materializing journal: {} is authoritative, overwriting {}", source, target);
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                syncFileAndDirectory(target);
                if (!Arrays.equals(sha256(source), sha256(target))) {
                    throw new IllegalStateException("materialize verification failed: sha256(source) != sha256(target)");
                }
                log.info("materialize verified: sha256 matches");
            }
            long end = scanToLastCompleteRecord(target);
            truncate(target, end);
            log.info("journal ready for append: {} head offset {}", target, end);
            return end;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** End offset of the last '\n'-framed, parseable record. Throws on a corrupt complete line. */
    public static long scanToLastCompleteRecord(Path path) {
        long end = 0;
        try (FramedReader reader = new FramedReader(path, 0)) {
            FramedReader.Framed f;
            while ((f = reader.next()) != null) {
                end = f.endOffset();
            }
        }
        return end;
    }

    private static void truncate(Path path, long end) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
            if (ch.size() > end) {
                // A torn tail means the process died mid-append: worth an operator's attention.
                log.warn("truncating torn tail of {}: discarding {} bytes after the last complete record at offset {}",
                    path, ch.size() - end, end);
                ch.truncate(end);
                ch.force(true);
            }
        }
    }

    /**
     * Make a newly created journal file durable: its data and its directory entry (SPEC §3.1).
     * Directory fsync is best effort on platforms that cannot open a directory for sync.
     */
    public static void syncFileAndDirectory(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
        Path dir = file.toAbsolutePath().getParent();
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException e) {
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                throw e;
            }
            log.debug("directory fsync unsupported on this platform, continuing", e);
        }
    }

    private static boolean sameFile(Path a, Path b) throws IOException {
        if (Files.exists(a) && Files.exists(b)) {
            return Files.isSameFile(a, b);
        }
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    public static byte[] sha256(Path path) throws IOException {
        try (InputStream in = new DigestInputStream(Files.newInputStream(path), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return ((DigestInputStream) in).getMessageDigest().digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
