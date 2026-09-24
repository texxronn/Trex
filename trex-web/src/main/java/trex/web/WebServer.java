package trex.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import trex.category.Categorizer;
import trex.grid.GridRoutes;
import trex.resolver.ResolverRoutes;
import trex.resolver.SequencerClient;
import trex.web.Web.HttpError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one web service (SPEC §5.4): browsing at {@code /}, the resolution workflow at
 * {@code /resolve}, one journal fold feeding both, one CSRF posture, one port.
 * <p>
 * Browsing and deciding share an origin on purpose. As two services they could not act on each
 * other's pages without widening the CSRF guard that protects the only endpoints able to change
 * anything (DECISIONS W1).
 */
public final class WebServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WebServer.class);

    private final JournalWatcher<JournalView> watcher;
    private final GridRoutes grid;
    private final ResolverRoutes resolver;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public WebServer(JournalWatcher<JournalView> watcher, SequencerClient sequencer, Categorizer categorizer,
                     String bindAddress, int port) {
        this(watcher, sequencer, categorizer, bindAddress, port, 15_000);
    }

    public WebServer(JournalWatcher<JournalView> watcher, SequencerClient sequencer, Categorizer categorizer,
                     String bindAddress, int port, long heartbeatMillis) {
        this.watcher = watcher;
        this.grid = new GridRoutes(watcher, categorizer, heartbeatMillis);
        this.resolver = new ResolverRoutes(watcher, sequencer, categorizer, heartbeatMillis);
        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
    }

    public WebServer start() {
        server.start();
        log.info("trex-web serving on {}", server.getAddress());
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Streams open across both halves, for the tests and the health line. */
    public int streamCount() {
        return grid.streamCount() + resolver.streamCount();
    }

    /**
     * Resolution lives under its own prefix so both pages can keep an {@code /api/events} stream
     * of their own without one shadowing the other.
     */
    private void handle(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        try {
            if (path.equals("/resolve") || path.startsWith("/resolve/") || path.startsWith("/api/resolve/")) {
                resolver.handle(ex, path);
            } else {
                grid.handle(ex, path);
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
        grid.close();
        resolver.close();
        executor.close();
    }

    public JournalWatcher<JournalView> watcher() {
        return watcher;
    }
}
