package trex.resolver;

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
 * Server-Sent Events for the resolver page (SPEC §5.4). Each client's handler thread (a virtual
 * thread) blocks until the journal state changes or the heartbeat interval passes. Changes are
 * coalesced: a client always receives the latest state, never a backlog.
 */
final class EventStreams implements AutoCloseable {

    static final int MAX_CLIENTS = 32;

    private final Supplier<byte[]> stateJson;
    private final long heartbeatMillis;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private static final class Client {
        final Semaphore changed = new Semaphore(0);
    }

    EventStreams(Supplier<byte[]> stateJson, long heartbeatMillis) {
        this.stateJson = stateJson;
        this.heartbeatMillis = heartbeatMillis;
    }

    /** Wake every connected client; called by the journal watcher on change. */
    void publish() {
        for (Client c : clients) {
            if (c.changed.availablePermits() == 0) {
                c.changed.release();
            }
        }
    }

    /** @return false if the client limit is reached (caller responds 503) */
    boolean tryAdmit() {
        return clients.size() < MAX_CLIENTS;
    }

    /** Serve one stream until the client disconnects or the server closes. */
    void stream(HttpExchange ex) throws IOException {
        Client client = new Client();
        clients.add(client);
        try {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("X-Accel-Buffering", "no");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            send(out, stateEvent());
            while (!closed) {
                boolean changed = client.changed.tryAcquire(heartbeatMillis, TimeUnit.MILLISECONDS);
                if (closed) {
                    break;
                }
                send(out, changed ? stateEvent() : ": ping\n\n".getBytes(StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            clients.remove(client);
        }
    }

    private byte[] stateEvent() {
        byte[] json = stateJson.get();
        byte[] head = "event: state\ndata: ".getBytes(StandardCharsets.UTF_8);
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

    int clientCount() {
        return clients.size();
    }

    @Override
    public void close() {
        closed = true;
        clients.forEach(c -> c.changed.release());
    }
}
