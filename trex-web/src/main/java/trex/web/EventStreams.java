package trex.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Server-Sent Events for the follower web pages (SPEC §5.4, §5.5). Each client's handler thread (a virtual
 * thread) blocks until the journal state changes or the heartbeat interval passes. Changes are
 * coalesced: a client always receives the latest state, never a backlog.
 */
public final class EventStreams implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EventStreams.class);

    public static final int MAX_CLIENTS = 32;

    private final String eventName;
    private final Supplier<byte[]> payloadJson;
    private final long heartbeatMillis;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private static final class Client {
        final Semaphore changed = new Semaphore(0);
    }

    /** Each message is {@code event: <eventName>} with the current {@code payloadJson} as data. */
    public EventStreams(String eventName, Supplier<byte[]> payloadJson, long heartbeatMillis) {
        this.eventName = eventName;
        this.payloadJson = payloadJson;
        this.heartbeatMillis = heartbeatMillis;
    }

    /** Wake every connected client; called by the journal watcher on change. */
    public void publish() {
        for (Client c : clients) {
            if (c.changed.availablePermits() == 0) {
                c.changed.release();
            }
        }
    }

    /** @return false if the client limit is reached (caller responds 503) */
    public boolean tryAdmit() {
        return clients.size() < MAX_CLIENTS;
    }

    /** Serve one stream until the client disconnects or the server closes. */
    public void stream(HttpExchange ex) throws IOException {
        Client client = new Client();
        clients.add(client);
        log.debug("SSE client connected to {} ({} of {} slots in use)", eventName, clients.size(), MAX_CLIENTS);
        try {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("X-Accel-Buffering", "no");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            send(out, event());
            while (!closed) {
                boolean changed = client.changed.tryAcquire(heartbeatMillis, TimeUnit.MILLISECONDS);
                if (closed) {
                    break;
                }
                send(out, changed ? event() : ": ping\n\n".getBytes(StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            clients.remove(client);
            log.debug("SSE client disconnected from {} ({} remaining)", eventName, clients.size());
        }
    }

    private byte[] event() {
        byte[] json = payloadJson.get();
        byte[] head = ("event: " + eventName + "\ndata: ").getBytes(StandardCharsets.UTF_8);
        byte[] tail = "\n\n".getBytes(StandardCharsets.UTF_8);
        byte[] event = new byte[head.length + json.length + tail.length];
        System.arraycopy(head, 0, event, 0, head.length);
        System.arraycopy(json, 0, event, head.length, json.length);
        System.arraycopy(tail, 0, event, head.length + json.length, tail.length);
        return event;
    }

    private static void send(OutputStream out, byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    public int clientCount() {
        return clients.size();
    }

    @Override
    public void close() {
        log.debug("closing {} stream with {} connected clients", eventName, clients.size());
        closed = true;
        clients.forEach(c -> c.changed.release());
    }
}
