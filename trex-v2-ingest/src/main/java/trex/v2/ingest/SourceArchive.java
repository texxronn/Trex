package trex.v2.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.zip.GZIPOutputStream;

/**
 * The source archive (V2-PROPOSAL.md §12.6): a gzip of the exact source bytes under a dated, human
 * name, at {@code <root>/sources/<Y>/<M>/<D>/<HHMMSS>-<name>.gz}. The ingest client owns this; it is
 * per-attempt and not deduped — the arrival log is the point. Evidence remains the machine copy.
 */
public final class SourceArchive {

    private SourceArchive() {}

    /** Write the source bytes and return the archived path. */
    public static Path write(Path root, byte[] content, String original, Instant at) {
        ZonedDateTime t = at.atZone(ZoneOffset.UTC);
        String dir = String.format("%04d/%02d/%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth());
        String stamp = String.format("%02d%02d%02d", t.getHour(), t.getMinute(), t.getSecond());
        String name = stamp + "-" + sanitize(original) + ".gz";
        Path folder = root.resolve("sources").resolve(dir);
        Path target = folder.resolve(name);
        try {
            Files.createDirectories(folder);
            Path tmp = folder.resolve(name + ".tmp");
            try (GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                gz.write(content);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot archive the source to " + target, e);
        }
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
}
