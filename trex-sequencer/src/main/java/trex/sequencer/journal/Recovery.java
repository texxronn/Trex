package trex.sequencer.journal;

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
                    Files.createFile(target);
                    syncFileAndDirectory(target);
                }
            } else {
                if (Files.notExists(source)) {
                    throw new IllegalStateException("journal.source does not exist: " + source);
                }
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                syncFileAndDirectory(target);
                if (!Arrays.equals(sha256(source), sha256(target))) {
                    throw new IllegalStateException("materialize verification failed: sha256(source) != sha256(target)");
                }
            }
            long end = scanToLastCompleteRecord(target);
            truncate(target, end);
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
