package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import trex.v2.hub.api.ReflowPreview;
import trex.v2.hub.api.ReflowRequest;
import trex.v2.log.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * {@code trex reflow --preview}: show what a candidate {@code categories.yaml} would change, served
 * by the hub's index (V2-PROPOSAL.md §9.3). It writes nothing; saving the file is the only gate.
 */
@Command(name = "reflow", mixinStandardHelpOptions = true,
    description = "Preview what a candidate rule set would change before saving it.")
public final class ReflowCommand implements Callable<Integer> {

    @Option(names = "--hub-url", required = true, description = "The hub base URL.")
    String hubUrl;

    @Option(names = "--categories", required = true, description = "Candidate categories.yaml.")
    Path categories;

    @Override
    public Integer call() throws Exception {
        String yaml = Files.readString(categories, StandardCharsets.UTF_8);
        String body = Json.mapper().writeValueAsString(new ReflowRequest(yaml));
        String base = hubUrl.endsWith("/") ? hubUrl.substring(0, hubUrl.length() - 1) : hubUrl;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/reflow/preview"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.err.println("reflow preview failed (HTTP " + response.statusCode() + "): " + response.body());
            return 1;
        }
        ReflowPreview preview = Json.mapper().readValue(response.body(), ReflowPreview.class);
        System.out.println("config revision: " + shortRev(preview.fromRevision()) + " -> " + shortRev(preview.toRevision()));
        System.out.println("categories: " + preview.categoriesMoved() + " moved");
        preview.moved().stream().limit(50)
            .forEach(m -> System.out.printf("  %-18s %s -> %s%n", m.externalId(), m.from(), m.to()));
        System.out.println("transfers: " + preview.transfersAdded() + " new, " + preview.transfersRemoved() + " unmatched");
        System.out.println("review: " + preview.reviewOpened() + " opened, " + preview.reviewCleared() + " cleared");
        return 0;
    }

    private static String shortRev(String revision) {
        if (revision == null) {
            return "(none)";
        }
        return revision.length() > 14 ? revision.substring(0, 14) + "…" : revision;
    }
}
