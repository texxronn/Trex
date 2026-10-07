package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import trex.v2.core.config.DeriveConfig;
import trex.v2.log.ConfigLoader;
import trex.v2.log.EvidenceStore;
import trex.v2.log.Json;
import trex.v2.log.LogCodec;
import trex.v2.log.Streams;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code trex stream export|ingest}: promotion is replay, not a copy (V2-PROPOSAL.md §14.1). Export
 * writes the journal as gzipped JSONL plus a manifest; ingest appends it line for line to another
 * sequencer, re-validating as it lands. The stream is plain JSONL and may be transformed, so long
 * as {@code n}, order and identity fields hold.
 */
@Command(name = "stream", mixinStandardHelpOptions = true,
    description = "Export the log as a stream, or ingest a stream into a sequencer.",
    subcommands = {StreamCommand.Export.class, StreamCommand.Ingest.class})
public final class StreamCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        picocli.CommandLine.usage(this, System.out);
        return 0;
    }

    /** {@code trex stream export}: the journal as gzipped JSONL plus a manifest. */
    @Command(name = "export", mixinStandardHelpOptions = true,
        description = "Export the journal as a gzipped JSONL stream and a manifest.")
    static final class Export implements Callable<Integer> {

        @Option(names = "--journal", required = true, description = "Path to trex.jsonl.")
        Path journal;

        @Option(names = "--config", required = true, description = "Config directory (for configRevision).")
        Path config;

        @Option(names = "--out", required = true, description = "Output .jsonl.gz.")
        Path out;

        @Option(names = "--since", description = "Export only lines with n >= this (a suffix).")
        Long since;

        @Override
        public Integer call() throws Exception {
            ConfigLoader.Loaded loaded = ConfigLoader.load(config);
            Streams.Manifest m = Streams.export(journal, out, since, loaded.config().configRevision(),
                DeriveConfig.DERIVE_VERSION);
            System.out.printf("exported %d line(s) to %s (head n=%d, offset %d, %s)%n",
                m.count(), out, m.headN(), m.headOffset(), m.counts());
            System.out.printf("manifest: %s%n", Streams.Manifest.sidecar(out));
            return 0;
        }
    }

    /** {@code trex stream ingest}: append a stream to a sequencer, validating as it lands. */
    @Command(name = "ingest", mixinStandardHelpOptions = true,
        description = "Append a stream (gzipped or plain) to a sequencer, skipping any prefix present.")
    static final class Ingest implements Callable<Integer> {

        @Option(names = "--file", required = true, description = "The stream file (.jsonl.gz or .jsonl).")
        Path file;

        @Option(names = "--config", required = true, description = "Config directory (config check).")
        Path config;

        @Option(names = "--sequencer-url", required = true, description = "The sequencer base URL.")
        String sequencerUrl;

        @Option(names = "--evidence", description = "Evidence store to check referenced ids against.")
        Path evidence;

        @Option(names = "--env", description = "Re-stamp every line's env to this 8-char code.")
        String env;

        @Option(names = "--chunk", defaultValue = "500", description = "Lines per request.")
        int chunk;

        @Override
        public Integer call() throws Exception {
            ConfigLoader.Loaded loaded = ConfigLoader.load(config);
            DeriveConfig cfg = loaded.config();
            List<String> lines = Streams.readLines(file);

            Path sidecar = Streams.Manifest.sidecar(file);
            if (Files.exists(sidecar)) {
                Streams.Manifest m = Streams.readManifest(sidecar);
                if (!cfg.configRevision().equals(m.configRevision())) {
                    System.err.printf("configRevision mismatch: stream=%s target=%s (refusing before any write)%n",
                        m.configRevision(), cfg.configRevision());
                    return 1;
                }
            }
            if (evidence != null) {
                EvidenceStore store = new EvidenceStore(evidence);
                for (String id : Streams.evidenceIds(lines)) {
                    if (!store.contains(id)) {
                        System.err.printf("missing evidence id %s (refusing)%n", id);
                        return 1;
                    }
                }
            }

            HttpClient http = HttpClient.newHttpClient();
            String base = trimUrl(sequencerUrl);
            long headN = headN(http, base);

            List<String> toSend = new ArrayList<>();
            for (String line : lines) {
                if (nOf(line) > headN) {
                    toSend.add(line);
                }
            }
            if (toSend.isEmpty()) {
                System.out.printf("nothing to ingest; target head n=%d%n", headN);
                return 0;
            }
            long firstN = nOf(toSend.getFirst());
            if (firstN != headN + 1) {
                System.err.printf("gap: stream resumes at n=%d but the target head is n=%d (fresh target or exact continuation only)%n",
                    firstN, headN);
                return 1;
            }
            if (env != null && !env.isBlank()) {
                List<String> stamped = new ArrayList<>(toSend.size());
                for (String line : toSend) {
                    stamped.add(restamp(line, env));
                }
                toSend = stamped;
            }

            long landed = 0;
            for (int i = 0; i < toSend.size(); i += chunk) {
                List<String> part = toSend.subList(i, Math.min(i + chunk, toSend.size()));
                trex.v2.sequencer.api.StreamResponse response = post(http, base, part);
                landed += response.appended();
                if (response.error() != null) {
                    System.err.printf("stopped at n=%s after %d line(s): %s%n",
                        response.stoppedAt(), landed, response.error());
                    return 1;
                }
            }
            System.out.printf("ingested %d line(s); head n=%d%n", landed, headN + landed);
            return 0;
        }

        private static String trimUrl(String url) {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }

        private static long nOf(String line) {
            return LogCodec.parse(line.getBytes(StandardCharsets.UTF_8)).n();
        }

        private static String restamp(String line, String env) throws Exception {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                (com.fasterxml.jackson.databind.node.ObjectNode) Json.mapper().readTree(line);
            node.put("env", env);
            return Json.mapper().writeValueAsString(node);
        }

        private static long headN(HttpClient http, String base) throws Exception {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/head"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("sequencer /head answered " + response.statusCode());
            }
            return Json.mapper().readTree(response.body()).path("n").asLong();
        }

        private static trex.v2.sequencer.api.StreamResponse post(HttpClient http, String base,
                                                                List<String> part) throws Exception {
            String body = String.join("\n", part) + "\n";
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/stream"))
                .header("Content-Type", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("sequencer /stream answered " + response.statusCode()
                    + ": " + response.body());
            }
            return Json.mapper().readValue(response.body(), trex.v2.sequencer.api.StreamResponse.class);
        }
    }
}
