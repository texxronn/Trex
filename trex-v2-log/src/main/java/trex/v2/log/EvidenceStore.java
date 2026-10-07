package trex.v2.log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The evidence store (V2-PROPOSAL.md §8.1, §5.4): the raw statement bytes, content-addressed and
 * compressed at {@code evidence/sha256/ab/cd/<hex>.gz}. It is the only thing that makes a parser
 * fix safe — "the PDF contained this text on this date" stays answerable after the file is gone.
 * Immutable: putting the same content twice writes nothing.
 */
public final class EvidenceStore {

    private final Path root;

    public EvidenceStore(Path root) {
        this.root = root;
    }

    /** The content id, {@code sha256:<hex>}, for raw bytes — whether or not they are stored yet. */
    public static String id(byte[] content) {
        return "sha256:" + hex(content);
    }

    /** Store raw source bytes; returns the id. A second put of the same content is a no-op. */
    public String put(byte[] content) {
        String hex = hex(content);
        Path path = pathForHex(hex);
        if (Files.notExists(path)) {
            try {
                Files.createDirectories(path.getParent());
                Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
                try (GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                    gz.write(content);
                }
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
                force(path);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot store evidence " + path, e);
            }
        }
        return "sha256:" + hex;
    }

    public boolean contains(String evidenceId) {
        return Files.exists(pathFor(evidenceId));
    }

    /** The on-disk path for an id; it may not exist yet. */
    public Path pathFor(String evidenceId) {
        return pathForHex(hexOf(evidenceId));
    }

    /** The original bytes, decompressed. */
    public byte[] get(String evidenceId) {
        Path path = pathFor(evidenceId);
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read evidence " + evidenceId, e);
        }
    }

    public record Entry(String id, Path path, long bytes) {}

    /** Every stored file. */
    public List<Entry> list() {
        Path base = root.resolve("sha256");
        if (Files.notExists(base)) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        try (var files = Files.walk(base)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".gz")).sorted().toList()) {
                String name = file.getFileName().toString();
                out.add(new Entry("sha256:" + name.substring(0, name.length() - ".gz".length()), file, size(file)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Re-hash every stored file; anything unreadable or not matching its name is returned. */
    public List<String> verify() {
        List<String> bad = new ArrayList<>();
        for (Entry entry : list()) {
            try {
                if (!id(get(entry.id())).equals(entry.id())) {
                    bad.add(entry.id());
                }
            } catch (RuntimeException e) {
                bad.add(entry.id());
            }
        }
        return bad;
    }

    private Path pathForHex(String hex) {
        return root.resolve("sha256").resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4))
            .resolve(hex + ".gz");
    }

    private static String hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hexOf(String evidenceId) {
        if (evidenceId == null || !evidenceId.startsWith("sha256:")) {
            throw new IllegalArgumentException("not an evidence id: " + evidenceId);
        }
        return evidenceId.substring("sha256:".length());
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    private static void force(Path path) throws IOException {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(path,
                java.nio.file.StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    }
}
