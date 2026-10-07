package trex.v2.log;

import trex.v2.core.Fact;
import trex.v2.core.LogLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Stream promotion (V2-PROPOSAL.md §14.1): export the journal as gzipped JSONL plus a manifest, and
 * read a stream back for ingest. The stream is the log line for line — facts, decisions and ingest
 * events, envelope included — so ordering, {@code occ}, {@code atMs}, ids and evidence ids all
 * arrive as written. Files are gzipped on export and accepted gzipped or plain on ingest, so
 * ordinary tools ({@code jq}, {@code zcat}) compose.
 */
public final class Streams {

    /**
     * The sidecar manifest: where the stream starts and ends, what it holds, and the config that
     * produced the source. Ingest refuses before writing when the target's config differs.
     */
    public record Manifest(long headN, long headOffset, long count, Map<String, Long> counts,
                           String configRevision, String deriveVersion) {

        public Manifest {
            counts = Map.copyOf(new TreeMap<>(counts));
        }

        /** The manifest beside a stream file: {@code <out>.manifest.json}. */
        public static Path sidecar(Path out) {
            return out.resolveSibling(out.getFileName() + ".manifest.json");
        }
    }

    private Streams() {}

    /**
     * Write the journal's complete lines to {@code out} as gzip JSONL, with the manifest beside it.
     *
     * @param sinceN when set, only lines with {@code n >= sinceN} (a suffix for resume/replication)
     */
    public static Manifest export(Path journal, Path out, Long sinceN, String configRevision,
                                  String deriveVersion) throws IOException {
        long headOffset = Recovery.scanToLastCompleteRecord(journal);
        byte[] all;
        try (FileChannel ch = FileChannel.open(journal, java.nio.file.StandardOpenOption.READ)) {
            int size = (int) Math.min(headOffset, ch.size());
            all = new byte[size];
            ByteBuffer buf = ByteBuffer.wrap(all);
            while (buf.hasRemaining() && ch.read(buf) >= 0) {
                // read to the end of the valid prefix
            }
        }
        Map<String, Long> counts = new TreeMap<>();
        long count = 0;
        long headN = 0;
        try (OutputStream raw = Files.newOutputStream(out);
             GZIPOutputStream gz = new GZIPOutputStream(raw)) {
            int start = 0;
            for (int i = 0; i <= all.length; i++) {
                if (i == all.length || all[i] == '\n') {
                    if (i > start) {
                        byte[] line = Arrays.copyOfRange(all, start, i);
                        LogLine parsed = LogCodec.parse(line);
                        if (sinceN == null || parsed.n() >= sinceN) {
                            gz.write(line);
                            gz.write('\n');
                            count++;
                            headN = Math.max(headN, parsed.n());
                            counts.merge(parsed.envelope().kind(), 1L, Long::sum);
                        }
                    }
                    start = i + 1;
                }
            }
        }
        Manifest manifest = new Manifest(headN, headOffset, count, counts, configRevision, deriveVersion);
        Files.writeString(Manifest.sidecar(out), Json.mapper().writeValueAsString(manifest),
            StandardCharsets.UTF_8);
        return manifest;
    }

    public static Manifest readManifest(Path manifestFile) throws IOException {
        return Json.mapper().readValue(Files.readString(manifestFile, StandardCharsets.UTF_8), Manifest.class);
    }

    /** Every non-blank line of a stream file, gzipped or plain (sniffed by its magic bytes). */
    public static List<String> readLines(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file)) {
            PushbackInputStream pb = new PushbackInputStream(raw, 2);
            int b1 = pb.read();
            int b2 = pb.read();
            if (b2 >= 0) {
                pb.unread(b2);
            }
            if (b1 >= 0) {
                pb.unread(b1);
            }
            boolean gzip = b1 == 0x1f && b2 == 0x8b;
            InputStream body = gzip ? new GZIPInputStream(pb) : pb;
            BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            List<String> out = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    out.add(line);
                }
            }
            return out;
        }
    }

    /** The evidence ids named by fact lines, for the ingest presence check (§14.1). */
    public static Set<String> evidenceIds(List<String> lines) {
        Set<String> out = new TreeSet<>();
        for (String line : lines) {
            LogLine parsed = LogCodec.parse(line.getBytes(StandardCharsets.UTF_8));
            if (parsed instanceof Fact f && f.evidenceId() != null && !f.evidenceId().isBlank()) {
                out.add(f.evidenceId());
            }
        }
        return out;
    }
}
