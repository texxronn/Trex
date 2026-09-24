package trex.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import trex.category.Categorizer;
import trex.core.CanonicalEvent;
import trex.gateway.grid.BrowseRoutes;
import trex.gateway.ledger.LedgerRoutes;
import trex.gateway.ledger.SequencerClient;
import trex.gateway.Web.HttpError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The consumer API (SPEC §5.7). One journal fold feeds both halves — the ordered line list the
 * table pages over, and the latest-line-per-id ledger that yields HELD/REVIEW — so a reader
 * switching tabs always sees one instant of the journal.
 * <p>
 * Every route is {@code /api/…}: this service has no pages. The browser never reaches it; trex-web
 * proxies to it (§5.4), and a future consumer (a native app, a script, the Firefly egress) calls
 * it directly and gets the same answers and the same checks.
 */
public final class GatewayServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    private final JournalWatcher<JournalView> watcher;
    private final BrowseRoutes browse;
    private final LedgerRoutes ledger;
    private final RuleRoutes ruleRoutes;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public GatewayServer(JournalWatcher<JournalView> watcher, SequencerClient sequencer, Rules rules,
                     String bindAddress, int port) {
        this(watcher, sequencer, rules, bindAddress, port, 15_000);
    }

    public GatewayServer(JournalWatcher<JournalView> watcher, SequencerClient sequencer, Rules rules,
                     String bindAddress, int port, long heartbeatMillis) {
        this.watcher = watcher;
        this.browse = new BrowseRoutes(watcher, rules, heartbeatMillis);
        this.ledger = new LedgerRoutes(watcher, sequencer, rules);
        // The writer sees the same lines the table does, so a proposal's numbers are the
        // numbers on screen rather than a second reading of the journal.
        this.ruleRoutes = new RuleRoutes(rules,
            () -> List.copyOf(watcher.status().view().ledger().latestLines()));
        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
    }

    public GatewayServer start() {
        server.start();
        log.info("trex-gateway serving on {}", server.getAddress());
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Open event streams, for the tests and the health line. One stream serves the whole API. */
    public int streamCount() {
        return browse.streamCount();
    }

    /**
     * The ledger half owns the endpoints about resolving; everything else is browsing. Both are
     * flat under {@code /api/} because there are no pages here to collide with (§5.7).
     */
    private static final java.util.Set<String> LEDGER_PATHS =
        java.util.Set.of("/api/ledger", "/api/decisions");

    private static boolean isRulePath(String path) {
        return path.equals("/api/rules") || path.startsWith("/api/rules/")
            || path.equals("/api/pins") || path.startsWith("/api/pins/")
            || path.equals("/api/proposal");
    }

    private void handle(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        try {
            if (LEDGER_PATHS.contains(path)) {
                ledger.handle(ex, path);
            } else if (isRulePath(path)) {
                ruleRoutes.handle(ex, path);
            } else {
                browse.handle(ex, path);
            }
        } catch (HttpError e) {
            log.debug("{} {} -> {}: {}", ex.getRequestMethod(), path, e.status(), e.getMessage());
            Web.error(ex, e.status(), e.getMessage());
        } catch (Exception e) {
            log.error("{} {} -> 500: unhandled failure", ex.getRequestMethod(), path, e);
            Web.error(ex, 500, "internal error: " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        browse.close();
        executor.close();
    }

    public JournalWatcher<JournalView> watcher() {
        return watcher;
    }
}
