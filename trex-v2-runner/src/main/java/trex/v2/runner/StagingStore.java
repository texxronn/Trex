package trex.v2.runner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * The staging inbox (V2-PROPOSAL.md §12.5). Uploads land here before ingest; it is transient — the
 * evidence store is the durable copy of the bytes and the facts are the ledger. The "ingested" tick
 * is NOT stored here: it is derived from the log by the hub, because evidence is written before
 * parsing and would otherwise mark a rejected file as done.
 *
 * <p>Each staged file carries its original name in a {@code .meta} sidecar, so the filename →
 * (sourceType, account) map still applies after the safe, generated name.
 */
public final class StagingStore {

    /** {@code sha256} is the evidence id's hash, so the hub can join it to {@code fact.evidence_id}. */
    public record Staged(String name, String original, long size, String sha256, String state, long at) {}

    private static final String META = ".meta";
    private static final String PART = ".part";

    private final Path root;
    private final Path done;
    private final Path failed;

    public StagingStore(Path root) {
        this.root = root;
        this.done = root.resolve("done");
        this.failed = root.resolve("failed");
    }

    public synchronized Staged put(byte[] bytes, String original) throws IOException {
        Files.createDirectories(root);
        String safe = sanitize(original);
        String name = System.currentTimeMillis() + "-" + safe;
        int n = 1;
        while (Files.exists(root.resolve(name)) || Files.exists(root.resolve(name + META))) {
            name = System.currentTimeMillis() + "-" + (n++) + "-" + safe;
        }
        Path target = root.resolve(name);
        Path tmp = root.resolve(name + PART);
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(root.resolve(name + META), original == null ? "" : original, StandardCharsets.UTF_8);
        return new Staged(name, original == null ? name : original, bytes.length, sha256Hex(bytes), "staged",
            Files.getLastModifiedTime(target).toMillis());
    }

    public synchronized List<Staged> list() {
        List<Staged> out = new ArrayList<>();
        out.addAll(read(root, "staged"));
        out.addAll(read(done, "done"));
        out.addAll(read(failed, "failed"));
        out.sort(Comparator.comparingLong(Staged::at).reversed());
        return out;
    }

    /** The original name a staged file was uploaded under, if known. */
    public String originalOf(String name) {
        for (Path dir : List.of(root, done, failed)) {
            Path meta = dir.resolve(name + META);
            if (Files.isRegularFile(meta)) {
                try {
                    return Files.readString(meta, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return name;
                }
            }
        }
        return name;
    }

    /** The absolute path of a staged file, in the inbox or in {@code done/}. */
    public Path resolve(String name) throws IOException {
        Path staged = safeResolve(root, name);
        if (Files.isRegularFile(staged)) {
            return staged;
        }
        Path archived = safeResolve(done, name);
        if (Files.isRegularFile(archived)) {
            return archived;
        }
        throw new IOException("no such staged file: " + name);
    }

    public synchronized boolean markDone(String name) throws IOException {
        return moveTo(done, name);
    }

    /** A file the sweep could not ingest (bad rows, or rejected): kept, out of the next sweep. */
    public synchronized boolean markFailed(String name) throws IOException {
        return moveTo(failed, name);
    }

    /**
     * The inbox files a sweep may take, by name: top level, settled (unmodified for
     * {@code settleMs}, so a file still being copied in is left for next time), and never a
     * dotfile or a partial ({@code .part}, {@code .tmp}) — Syncthing and scp both write those first.
     */
    public synchronized List<String> settled(long nowMs, long settleMs) {
        List<String> out = new ArrayList<>();
        for (Staged s : read(root, "staged")) {
            String n = s.name();
            if (n.startsWith(".") || n.endsWith(".tmp") || nowMs - s.at() < settleMs) {
                continue;
            }
            out.add(n);
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private boolean moveTo(Path dir, String name) throws IOException {
        Path src = safeResolve(root, name);
        if (!Files.isRegularFile(src)) {
            return false;
        }
        Files.createDirectories(dir);
        Files.move(src, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        Path meta = root.resolve(name + META);
        if (Files.isRegularFile(meta)) {
            Files.move(meta, dir.resolve(name + META), StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    private List<Staged> read(Path dir, String state) {
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                List<Staged> out = new ArrayList<>();
                for (Path file : files.toList()) {
                    String fileName = file.getFileName().toString();
                    if (!Files.isRegularFile(file) || fileName.endsWith(PART) || fileName.endsWith(META)) {
                        continue;
                    }
                    out.add(new Staged(fileName, originalOf(fileName), size(file), sha256Hex(read(file)),
                        state, mtime(file)));
                }
                return out;
            } catch (IOException e) {
                throw new UncheckedIOException("cannot list " + dir, e);
            }
        }
        return List.of();
    }

    private static Path safeResolve(Path base, String name) {
        if (name == null || name.isBlank()
            || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("bad staged name: " + name);
        }
        return base.resolve(name).normalize();
    }

    static String sanitize(String original) {
        String base = original == null || original.isBlank() ? "upload" : original;
        StringBuilder sb = new StringBuilder();
        for (char c : base.toCharArray()) {
            sb.append((Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') ? c : '_');
        }
        String out = sb.toString();
        if (out.length() > 120) {
            out = out.substring(out.length() - 120);
        }
        return out.isBlank() ? "upload" : out;
    }

    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] read(Path file) throws IOException {
        return Files.readAllBytes(file);
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    private static long mtime(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }
}
