package trex.egress.firefly;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import trex.core.CanonicalEvent;
import trex.core.Confidence;
import trex.core.EventState;
import trex.core.Provenance;
import trex.core.TypeHint;
import trex.journal.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens when Firefly says no. SPEC §5.8.
 * <p>
 * The pass used to count a refusal and carry on, which on a 1751-transaction run means the one
 * legible error scrolls past inside hundreds of lines and the exit code is the only survivor.
 * A transient failure is retried; anything else stops the pass where it happened and says so.
 */
class FailFastTest {

    private HttpServer firefly;
    private HttpServer gateway;
    private final AtomicInteger posts = new AtomicInteger();
    private volatile int status = 200;
    private volatile int failFirst;

    private static final AccountMap ACCOUNTS = new AccountMap(Map.of(
        "ing-salary", new AccountMap.Entry("ING Salary", AccountMap.Kind.ASSET, "3")));

    @BeforeEach
    void start() throws IOException {
        firefly = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        firefly.createContext("/api/v1/transactions", ex -> {
            int attempt = posts.incrementAndGet();
            if (attempt <= failFirst) {
                respond(ex, status, "{\"message\":\"the instance is restarting\"}");
                return;
            }
            respond(ex, status == 200 ? 200 : status,
                status == 200 ? "{\"data\":{\"id\":\"77\"}}" : "{\"message\":\"no such currency\"}");
        });
        firefly.start();

        gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/api/snapshot", ex -> respond(ex, 200, snapshot("ing-salary")));
        gateway.start();
    }

    @AfterEach
    void stop() {
        firefly.stop(0);
        gateway.stop(0);
    }

    // ---------------------------------------------------------------- retry

    /**
     * A container restart mid-pass is the ordinary case, not a bug. Two 503s then a 200 is one
     * transaction posted, not a dead run.
     */
    @Test
    void aTransientFailureIsRetried() throws Exception {
        status = 503;
        failFirst = 2;
        // The third attempt succeeds; flip the stub to success for it.
        firefly.removeContext("/api/v1/transactions");
        firefly.createContext("/api/v1/transactions", ex -> {
            int attempt = posts.incrementAndGet();
            if (attempt <= 2) {
                respond(ex, 503, "{\"message\":\"restarting\"}");
            } else {
                respond(ex, 200, "{\"data\":{\"id\":\"77\"}}");
            }
        });

        FireflyEgress.Outcome outcome = run(new FireflyClient.Retry(3, 1, 5));
        assertEquals(1, outcome.posted());
        assertEquals(3, posts.get(), "it should have taken exactly three attempts");
    }

    /** Once the attempts are spent it is terminal — the retry is not a way to hide a broken row. */
    @Test
    void anExhaustedRetryStopsThePass() {
        status = 503;
        failFirst = Integer.MAX_VALUE;

        var e = assertThrows(FireflyClient.Unreachable.class, () -> run(new FireflyClient.Retry(2, 1, 5)));
        assertTrue(e.getMessage().contains("all 2 attempt"), e.getMessage());
        assertEquals(2, posts.get());
    }

    /**
     * A 4xx is Firefly saying the request is wrong. Repeating it changes nothing but the clock,
     * and the message is the only thing that will tell you what to fix — so it surfaces whole,
     * naming the transaction, rather than becoming a number in a summary.
     */
    @Test
    void aRefusalIsNeverRetriedAndNamesTheTransaction() {
        status = 422;
        failFirst = 0;

        var e = assertThrows(FireflyEgress.Refused.class, () -> run(new FireflyClient.Retry(5, 1, 5)));
        assertEquals(1, posts.get(), "a 422 must not be retried");
        assertTrue(e.getMessage().contains("e1"), e.getMessage());
        assertTrue(e.getMessage().contains("ing-salary"), e.getMessage());
        assertTrue(e.getMessage().contains("no such currency"), e.getMessage());
    }

    // ---------------------------------------------------------------- preflight

    /**
     * Startup reconciles firefly.yaml against the instance, but the journal moves underneath it:
     * ingest a statement for a new account and the next pass meets a ref the file has never heard
     * of. Finding that at transaction 900 aborts with 899 already posted and nobody able to say
     * where it stopped. Finding it before the first write costs nothing.
     */
    @Test
    void anUnmappedAccountStopsThePassBeforeAnythingIsWritten() {
        // Two rows, and only the SECOND is unmapped. With one row the check cannot tell a
        // preflight from a crash on the first post — which is exactly the bug being guarded.
        gateway.removeContext("/api/snapshot");
        gateway.createContext("/api/snapshot", ex ->
            respond(ex, 200, snapshot("ing-salary", "cba-netsaver")));

        var e = assertThrows(FireflyEgress.Refused.class, () -> run(FireflyClient.Retry.NONE));
        assertTrue(e.getMessage().contains("cba-netsaver"), e.getMessage());
        assertTrue(e.getMessage().contains("nothing has been written"), e.getMessage());
        assertEquals(0, posts.get(), "the mapped row was posted before the unmapped one was found");
    }

    /** Every unmapped ref at once: fixing config one error per run is its own kind of slow. */
    @Test
    void itNamesEveryUnmappedAccountNotJustTheFirst() {
        gateway.removeContext("/api/snapshot");
        gateway.createContext("/api/snapshot", ex ->
            respond(ex, 200, snapshot("cba-netsaver", "ing-orange")));

        var e = assertThrows(FireflyEgress.Refused.class, () -> run(FireflyClient.Retry.NONE));
        assertTrue(e.getMessage().contains("cba-netsaver"), e.getMessage());
        assertTrue(e.getMessage().contains("ing-orange"), e.getMessage());
    }

    // ---------------------------------------------------------------- harness

    private FireflyEgress.Outcome run(FireflyClient.Retry retry) throws Exception {
        try (ProjectionCache cache = new ProjectionCache(null)) {
            FireflyEgress egress = new FireflyEgress(
                new GatewayClient(URI.create("http://127.0.0.1:" + gateway.getAddress().getPort())),
                new FireflyClient(URI.create("http://127.0.0.1:" + firefly.getAddress().getPort()),
                    "t", retry),
                cache, ACCOUNTS, false);
            return egress.run(new PrintStream(new ByteArrayOutputStream()));
        }
    }

    private static String snapshot(String... accountRefs) {
        StringBuilder rows = new StringBuilder();
        StringBuilder categories = new StringBuilder();
        try {
            for (int i = 0; i < accountRefs.length; i++) {
                CanonicalEvent line = new CanonicalEvent(i + 1, "e" + (i + 1), accountRefs[i], null,
                    "AUD", LocalDate.of(2026, 1, 1), -170, 0, "BUNNINGS 339000", "BUNNINGS 339000",
                    TypeHint.WITHDRAWAL, null, null, null, EventState.EXTERNAL, Confidence.HIGH,
                    List.of(), Provenance.BANK, "ing-csv", null, null, null, null, null, null,
                    Instant.EPOCH);
                if (i > 0) {
                    rows.append(",");
                    categories.append(",");
                }
                rows.append(Json.mapper().writeValueAsString(line));
                categories.append("\"%d\":{\"category\":\"DISCRETIONARY\",\"origin\":\"RULE\",\"why\":\"rule #1\"}"
                    .formatted(i + 1));
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return """
            {"asOfN":%d,"total":%d,"rulesRevision":"r1","rows":[%s],"categories":{%s}}
            """.formatted(accountRefs.length, accountRefs.length, rows, categories);
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
    }
}
