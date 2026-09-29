package trex.v2.egress.archive;

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

/**
 * The byte mirror (V2-PROPOSAL.md §11, §14): a second copy of the log, byte for byte, on another
 * disk. The mirror is the truth's twin, not a projection — it copies journal lines and never
 * resolves them. It appends only what the source has already fsynced and verifies by SHA-256.
 */
public final class ArchiveMirror {

    private ArchiveMirror() {}

    public record Result(long bytes, boolean copied) {}

    /**
     * Make {@code target} a byte-identical copy of {@code source}. If the target is already a
     * prefix of the source it appends the remainder; if the two have diverged it refuses, because a
     * mirror that quietly differs is worse than none.
     */
    public static Result mirrorJournal(Path source, Path target) throws IOException {
        if (Files.notExists(source)) {
            throw new IOException("journal does not exist: " + source);
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        if (Files.notExists(target)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            syncFileAndDirectory(target);
            verify(source, target);
            return new Result(Files.size(target), true);
        }
        long targetSize = Files.size(target);
        long sourceSize = Files.size(source);
        if (targetSize == sourceSize) {
            if (!Arrays.equals(sha256(source), sha256(target))) {
                throw new IOException("archive has diverged from the journal: " + target);
            }
            return new Result(targetSize, false);
        }
        if (targetSize > sourceSize || !prefixEquals(source, target, targetSize)) {
            throw new IOException("archive is not a prefix of the journal; refusing to overwrite: " + target);
        }
        try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            in.position(targetSize);
            long position = targetSize;
            long remaining = sourceSize - targetSize;
            while (remaining > 0) {
                long written = in.transferTo(position, remaining, out);
                if (written <= 0) {
                    break;
                }
                position += written;
                remaining -= written;
            }
            out.force(true);
        }
        syncFileAndDirectory(target);
        verify(source, target);
        return new Result(Files.size(target), true);
    }

    /** Copy every evidence file the archive does not already have, by name. */
    public static int copyEvidence(Path sourceDir, Path targetDir) throws IOException {
        if (Files.notExists(sourceDir)) {
            return 0;
        }
        int copied = 0;
        Files.createDirectories(targetDir);
        try (var files = Files.walk(sourceDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path destination = targetDir.resolve(sourceDir.relativize(file).toString());
                if (Files.notExists(destination) || Files.size(destination) != Files.size(file)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            }
        }
        return copied;
    }

    private static boolean prefixEquals(Path source, Path target, long length) throws IOException {
        byte[] a = new byte[(int) Math.min(length, 1 << 20)];
        long position = 0;
        while (position < length) {
            int chunk = (int) Math.min(a.length, length - position);
            try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ);
                 FileChannel out = FileChannel.open(target, StandardOpenOption.READ)) {
                in.position(position);
                out.position(position);
                java.nio.ByteBuffer ba = java.nio.ByteBuffer.wrap(a, 0, chunk);
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(new byte[chunk]);
                in.read(ba);
                out.read(bb);
                if (!Arrays.equals(ba.array(), 0, chunk, bb.array(), 0, chunk)) {
                    return false;
                }
            }
            position += chunk;
        }
        return true;
    }

    private static void verify(Path source, Path target) throws IOException {
        if (!Arrays.equals(sha256(source), sha256(target))) {
            throw new IOException("mirror verification failed: sha256 differs for " + target);
        }
    }

    static byte[] sha256(Path path) throws IOException {
        try (InputStream in = new DigestInputStream(Files.newInputStream(path),
                MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return ((DigestInputStream) in).getMessageDigest().digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void syncFileAndDirectory(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
        try (FileChannel ch = FileChannel.open(file.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
            ch.force(true);
        } catch (UncheckedIOException e) {
            // directory fsync is best effort on platforms that cannot open a directory
        }
    }
}
