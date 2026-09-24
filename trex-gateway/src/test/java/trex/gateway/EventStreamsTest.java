package trex.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventStreamsTest {

    /** A client hanging up is the normal end of a stream, not a failure the caller has to handle. */
    @Test
    void clientDisconnectEndsTheStreamQuietly() throws Exception {
        EventStreams events = new EventStreams("head", () -> "{}".getBytes(StandardCharsets.UTF_8), 5);
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> {
            try {
                events.stream(ex);
                outcome.complete(null);
            } catch (Throwable t) {
                outcome.complete(t);
            } finally {
                ex.close();
            }
        });
        server.start();
        try {
            try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
                OutputStream out = socket.getOutputStream();
                out.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                InputStream in = socket.getInputStream();
                String received = "";
                byte[] buf = new byte[1024];
                while (!received.contains("event: head")) {
                    int read = in.read(buf);
                    assertTrue(read > 0, "stream ended before the first event");
                    received += new String(buf, 0, read, StandardCharsets.UTF_8);
                }
                socket.setSoLinger(true, 0); // hang up with a reset, as a closed browser tab can
            }
            assertNull(outcome.get(10, TimeUnit.SECONDS));
            assertEquals(0, events.clientCount());
        } finally {
            events.close();
            server.stop(0);
        }
    }
}
