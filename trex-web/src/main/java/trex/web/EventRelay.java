package trex.web;

import com.sun.net.httpserver.HttpExchange;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One SSE connection to the gateway, fanned out to every open browser tab (SPEC §5.4).
 * <p>
 * The alternative — each tab holding its own stream against the gateway — would multiply a
 * connection that exists to say "something changed" by however many tabs happen to be open, and
 * would put the gateway's stream cap in the way of a person with three windows.
 * <p>
 * The relay reconnects on its own and keeps serving browser clients while it is down; they simply
 * stop hearing about changes, which is what the page's own polling fallback is for.
 */
public final class EventRelay implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EventRelay.class);

    private static final int MAX_CLIENTS = 32;
    private static final long RECONNECT_MS = 2_000;

    private final Upstream upstream;
    private final String path;
    private final Set<OutputStream> clients = ConcurrentHashMap.newKeySet();
    private final AtomicReference<byte[]> lastFrame = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread pump;

    public EventRelay(Upstream upstream, String path) {
        this.upstream = upstream;
        this.path = path;
    }

    public EventRelay start() {
        pump = Thread.ofVirtual().name("sse-relay").start(this::pumpForever);
        return this;
    }

    /** Attach a browser client. It gets the last frame at once, so a new tab is never blank. */
    public void stream(HttpExchange ex) throws IOException {
        if (clients.size() >= MAX_CLIENTS) {
            Web.error(ex, 503, "too many event streams");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        clients.add(out);
        try {
            byte[] frame = lastFrame.get();
            if (frame != null) {
                out.write(frame);
                out.flush();
            }
            // Hold the exchange open; the pump writes to it until the client goes away.
            while (running.get() && clients.contains(out)) {
                Thread.sleep(500);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            clients.remove(out);
        }
    }

    public int clientCount() {
        return clients.size();
    }

    private void pumpForever() {
        while (running.get()) {
            try {
                HttpResponse<InputStream> response = openOrNull();
                if (response == null) {
                    Thread.sleep(RECONNECT_MS);
                    continue;
                }
                try (InputStream body = response.body()) {   // the response itself is not closeable
                    read(body);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.debug("event relay dropped: {}", e.toString());
            }
            if (running.get()) {
                try {
                    Thread.sleep(RECONNECT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private HttpResponse<InputStream> openOrNull() {
        try {
            HttpResponse<InputStream> r = upstream.openStream(path);
            if (r.statusCode() != 200) {
                r.body().close();
                return null;
            }
            log.info("event relay connected to {}{}", upstream.base(), path);
            return r;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * SSE frames are separated by a blank line. Frames are forwarded whole and unparsed — trex-web
     * has no business knowing what the gateway is announcing.
     */
    private void read(InputStream in) throws IOException {
        StringBuilder frame = new StringBuilder();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                frame.append(line).append('\n');
                if (line.isEmpty()) {
                    byte[] bytes = frame.toString().getBytes(StandardCharsets.UTF_8);
                    // A heartbeat comment is forwarded but not remembered: replaying it to a new
                    // tab would tell it nothing, where replaying the last real frame tells it
                    // everything it missed.
                    if (frame.charAt(0) != ':') {
                        lastFrame.set(bytes);
                    }
                    broadcast(bytes);
                    frame.setLength(0);
                }
            }
        }
    }

    private void broadcast(byte[] bytes) {
        for (OutputStream out : clients) {
            try {
                out.write(bytes);
                out.flush();
            } catch (IOException e) {
                clients.remove(out);   // the tab closed; the exchange will be closed by its handler
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (pump != null) {
            pump.interrupt();
        }
        clients.clear();
    }
}
