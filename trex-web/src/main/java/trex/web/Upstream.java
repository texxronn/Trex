package trex.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one service trex-web talks to: trex-gateway (SPEC §5.4, §5.7).
 * <p>
 * Bytes in, bytes out. Nothing here parses a payload, because any meaning trex-web attached to a
 * response would be a second answer to a question the gateway has already answered — a filter it
 * read differently, a total it rounded differently. The proxy's only opinions are about transport.
 */
public final class Upstream {

    private static final Logger log = LoggerFactory.getLogger(Upstream.class);

    /** A response body worth remembering: small, JSON, and the whole answer to a GET. */
    private static final int MAX_CACHED_BYTES = 8 * 1024 * 1024;

    /**
     * Headers that describe the connection rather than the payload. Copying these through a proxy
     * is how you end up serving a chunked body with a stale Content-Length.
     */
    private static final Set<String> HOP_BY_HOP = Set.of(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailer", "transfer-encoding", "upgrade", "content-length", "host");

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    private final URI base;

    /**
     * The last good snapshot per path, so a gateway restart shows a stale-but-labelled page
     * rather than an empty one (§5.4). Bytes only: trex-web never learns what they mean.
     */
    private final java.util.Map<String, AtomicReference<byte[]>> lastGood = new java.util.concurrent.ConcurrentHashMap<>();

    public Upstream(URI base) {
        this.base = base;
    }

    public record Reply(int status, byte[] body, String contentType, boolean stale) {}

    /** GET a path with its query, returning the gateway's answer or the last one it gave. */
    public Reply get(String path, String rawQuery) {
        URI target = base.resolve(path + (rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery));
        try {
            HttpResponse<byte[]> r = http.send(
                HttpRequest.newBuilder(target).GET().timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() == 200 && r.body().length <= MAX_CACHED_BYTES) {
                lastGood.computeIfAbsent(cacheKey(path, rawQuery), _ -> new AtomicReference<>()).set(r.body());
            }
            return new Reply(r.statusCode(), r.body(), contentType(r), false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            byte[] cached = cached(path, rawQuery);
            if (cached == null) {
                log.warn("gateway unreachable at {} and nothing cached: {}", target, e.toString());
                return new Reply(502, ("{\"error\":\"gateway unreachable: " + e.getClass().getSimpleName() + "\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json", false);
            }
            // Stale beats blank: the page labels it and keeps working until the gateway returns.
            log.warn("gateway unreachable at {}; serving the last good answer", target);
            return new Reply(200, cached, "application/json", true);
        }
    }

    /** POST a body through unchanged, including the response — the gateway decides, not this. */
    public Reply post(String path, byte[] body, String contentType, String adminHeader) {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", contentType == null ? "application/json" : contentType);
        // X-Trex-Admin is forwarded because the gateway requires it of every caller (§5.7).
        if (adminHeader != null) {
            b.header("X-Trex-Admin", adminHeader);
        }
        // The browser's Origin is deliberately NOT forwarded. It describes the browser's
        // relationship to trex-web, and this hop is trex-web to trex-gateway on another port:
        // forwarded, it can only ever mismatch the gateway's Host and 403 every real browser
        // write, while proving nothing about the original request. The CSRF decision belongs to
        // the edge that faces the browser and has already been made by the time we get here.
        //
        // This was a live bug: curl sends no Origin and worked, a browser sends one and every
        // Apply and every decision returned 403.

        try {
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(r.statusCode(), r.body(), contentType(r), false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Never served from cache: a write that did not reach the gateway did not happen.
            return new Reply(502, ("{\"error\":\"gateway unreachable: " + e.getClass().getSimpleName() + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json", false);
        }
    }

    /** The SSE stream, opened once and fanned out by {@link EventRelay}. */
    public HttpResponse<java.io.InputStream> openStream(String path) throws IOException, InterruptedException {
        return http.send(
            HttpRequest.newBuilder(base.resolve(path))
                .GET()
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofHours(24))
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    }

    public URI base() {
        return base;
    }

    static boolean isHopByHop(String header) {
        return HOP_BY_HOP.contains(header.toLowerCase(java.util.Locale.ROOT));
    }

    private byte[] cached(String path, String rawQuery) {
        AtomicReference<byte[]> ref = lastGood.get(cacheKey(path, rawQuery));
        return ref == null ? null : ref.get();
    }

    /** Query included: page 2 of a filter is a different answer from page 1. */
    private static String cacheKey(String path, String rawQuery) {
        return path + "?" + (rawQuery == null ? "" : rawQuery);
    }

    private static String contentType(HttpResponse<?> r) {
        List<String> values = r.headers().allValues("content-type");
        return values.isEmpty() ? "application/json" : values.getFirst();
    }
}
