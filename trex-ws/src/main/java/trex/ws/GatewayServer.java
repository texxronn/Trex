package trex.ws;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import trex.category.Categorizer;
import trex.core.CanonicalEvent;
import trex.ws.grid.BrowseRoutes;
import trex.ws.ledger.LedgerRoutes;
import trex.ws.ledger.SequencerClient;
import trex.ws.Web.HttpError;

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
    private final CashRoutes cashRoutes;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Which routes a listener carries. SPEC §5.7.
     * <p>
     * This is the boundary that used to be a process boundary: while the rule writer was its own
     * service it bound loopback and only the page server was ever exposed. Merging them removed
     * that, so the split is explicit here instead — and it is made at <b>construction</b>, so a
     * mutating handler is never <em>registered</em> on a read listener rather than merely guarded
     * there. A guard can be bypassed by a routing mistake; an absent route cannot.
     */
    public enum Role {
        /**
         * Everything: pages, reads and every write. Loopback only, always.
         * <p>
         * This is the listener you use on your own machine, and the pages are served here too —
         * otherwise the resolve page could not post a decision and the cash form could not post a
         * line, which is most of what the UI is for.
         */
        ADMIN,
        /**
         * Pages and reads only. Safe to bind to another interface (§9) — this is what makes the
         * UI reachable from a phone without putting the rule writer on the network. The same
         * pages are served, and a write attempted from them fails with 404 because the route is
         * not there: a read-only view of the same thing, not a different thing.
         */
        READ;

        boolean allows(String path) {
            return this == ADMIN || !MUTATING.contains(path) && !isRulePath(path);
        }
    }

    /** Paths that change state. Never registered on a READ listener. */
    private static final java.util.Set<String> MUTATING =
        java.util.Set.of("/api/decisions", "/api/cash");

    private final Role role;

    public GatewayServer(JournalWatcher<JournalView> watcher, SequencerClient sequencer, Rules rules,
                     String bindAddress, int port) {
        this(watcher, sequencer, rules, bindAddress, port, 15_000, Role.ADMIN);
    }

    public GatewayServer(JournalWatcher<JournalView> watcher, SequencerClient sequencer, Rules rules,
                     String bindAddress, int port, long heartbeatMillis) {
        this(watcher, sequencer, rules, bindAddress, port, heartbeatMillis, Role.ADMIN);
    }

    public GatewayServer(JournalWatcher<JournalView> watcher, SequencerClient sequencer, Rules rules,
                     String bindAddress, int port, long heartbeatMillis, Role role) {
        this(watcher, sequencer, rules, null, bindAddress, port, heartbeatMillis, role);
    }

    public GatewayServer(JournalWatcher<JournalView> watcher, SequencerClient sequencer, Rules rules,
                     trex.core.account.AccountRegistry accounts,
                     String bindAddress, int port, long heartbeatMillis, Role role) {
        this.role = role;
        if (role == Role.ADMIN && !bindAddress.equals("127.0.0.1")) {
            // Not configurable, and not a warning: this listener writes the files that decide how
            // every consumer reads the journal, and it has no authentication (§5.7).
            throw new IllegalArgumentException(
                "the admin listener binds 127.0.0.1 only, not " + bindAddress);
        }
        this.watcher = watcher;
        this.browse = new BrowseRoutes(watcher, rules, heartbeatMillis);
        this.ledger = new LedgerRoutes(watcher, sequencer, rules);
        // The writer sees the same lines the table does, so a proposal's numbers are the
        // numbers on screen rather than a second reading of the journal.
        this.cashRoutes = new CashRoutes(accounts, sequencer);
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
        log.info("trex-ws {} listener on {}", role.name().toLowerCase(java.util.Locale.ROOT), server.getAddress());
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
    /** The registry, and the one way a line enters without a bank behind it (§5.7). */
    private static final java.util.Set<String> CASH_PATHS =
        java.util.Set.of("/api/accounts", "/api/cash");

    private static final java.util.Set<String> LEDGER_PATHS =
        java.util.Set.of("/api/ledger", "/api/decisions");

    private static boolean isRulePath(String path) {
        return path.equals("/api/rules") || path.startsWith("/api/rules/")
            || path.equals("/api/pins") || path.startsWith("/api/pins/")
            || path.equals("/api/proposal") || path.equals("/api/worklist");
    }

    private void handle(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        try {
            if (!role.allows(path)) {
                // 404, not 403: a route this listener does not carry does not exist here, and
                // saying "forbidden" would advertise it.
                throw new HttpError(404, "not found");
            }
            if (!path.startsWith("/api/")) {
                PageServer.servePage(ex, path);
            } else if (CASH_PATHS.contains(path)) {
                cashRoutes.handle(ex, path);
            } else if (LEDGER_PATHS.contains(path)) {
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
