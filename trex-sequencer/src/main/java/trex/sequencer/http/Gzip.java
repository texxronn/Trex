package trex.sequencer.http;

import com.sun.net.httpserver.HttpExchange;
import trex.sequencer.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Negotiated gzip, both directions, manual (SPEC §3.6). Every handler reads and writes through
 * {@link #readBody} and {@link #writeJson}. gzip is wire-only: it never reaches the journal.
 */
public final class Gzip {

    /** Decompressed request body exceeded the cap. */
    public static final class PayloadTooLargeException extends IOException {
        PayloadTooLargeException(long cap) {
            super("request body exceeds " + cap + " bytes (decompressed)");
        }
    }

    /** Content-Encoding other than gzip/identity. */
    public static final class UnsupportedEncodingException extends IOException {
        UnsupportedEncodingException(String encoding) {
            super("unsupported Content-Encoding: " + encoding);
        }
    }

    private Gzip() {}

    /** Degzip iff Content-Encoding: gzip (never sniffed); cap applied to decompressed bytes while streaming. */
    public static byte[] readBody(HttpExchange ex, long maxDecompressedBytes) throws IOException {
        String encoding = ex.getRequestHeaders().getFirst("Content-Encoding");
        InputStream in = ex.getRequestBody();
        if (encoding != null && encoding.trim().equalsIgnoreCase("gzip")) {
            in = new GZIPInputStream(in);
        } else if (encoding != null && !encoding.isBlank() && !encoding.trim().equalsIgnoreCase("identity")) {
            throw new UnsupportedEncodingException(encoding);
        }
        try (InputStream body = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int read;
            while ((read = body.read(buf)) != -1) {
                total += read;
                if (total > maxDecompressedBytes) {
                    throw new PayloadTooLargeException(maxDecompressedBytes);
                }
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        }
    }

    /** gzip iff the request advertised Accept-Encoding: gzip, regardless of the request's own coding. */
    public static void writeJson(HttpExchange ex, int status, Object obj) throws IOException {
        byte[] json = Json.mapper().writeValueAsBytes(obj);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        if (acceptsGzip(ex.getRequestHeaders().get("Accept-Encoding"))) {
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
            ex.sendResponseHeaders(status, 0);
            try (OutputStream out = new GZIPOutputStream(ex.getResponseBody())) {
                out.write(json);
            }
        } else {
            ex.sendResponseHeaders(status, json.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(json);
            }
        }
    }

    static boolean acceptsGzip(List<String> headerValues) {
        if (headerValues == null) {
            return false;
        }
        for (String header : headerValues) {
            for (String part : header.split(",")) {
                String[] tokens = part.trim().split(";");
                if (!tokens[0].trim().equalsIgnoreCase("gzip")) {
                    continue;
                }
                boolean zeroQ = false;
                for (int i = 1; i < tokens.length; i++) {
                    String param = tokens[i].trim().replace(" ", "");
                    if (param.equalsIgnoreCase("q=0") || param.matches("(?i)q=0\\.0{0,3}")) {
                        zeroQ = true;
                    }
                }
                if (!zeroQ) {
                    return true;
                }
            }
        }
        return false;
    }
}
