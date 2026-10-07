package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * {@code trex snapshot}: ask the sequencer for a journal snapshot (V2-PROPOSAL.md §12.6) — a dated
 * gzip copy of the log in the archive, never a rotation. Sync by default; {@code --async} returns as
 * soon as the copy is accepted.
 */
@Command(name = "snapshot", mixinStandardHelpOptions = true,
    description = "Ask the sequencer for a journal snapshot: a dated gzip copy in the archive.")
public final class SnapshotCommand implements Callable<Integer> {

    @Option(names = "--sequencer-url", required = true, description = "The running sequencer base URL.")
    String sequencerUrl;

    @Option(names = "--async", description = "Return as soon as the snapshot is accepted.")
    boolean async;

    @Override
    public Integer call() throws Exception {
        String base = sequencerUrl.endsWith("/") ? sequencerUrl.substring(0, sequencerUrl.length() - 1) : sequencerUrl;
        URI uri = URI.create(base + "/maintenance/snapshot?sync=" + (!async));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).POST(
            HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            System.err.println("snapshot failed: HTTP " + response.statusCode() + " " + response.body());
            return 1;
        }
        System.out.println(response.body());
        return 0;
    }
}
