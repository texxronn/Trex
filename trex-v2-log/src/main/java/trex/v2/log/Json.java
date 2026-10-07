package trex.v2.log;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The one JSON mapper for the v2 log and APIs (V2-PROPOSAL.md §6). Strict on the way in (an
 * unknown property is an error, nulls are never coerced into primitives) and deterministic on the
 * way out ({@code ORDER_MAP_ENTRIES_BY_KEYS} is on, so a map serialises identically every run).
 */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private Json() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Parse one JSON value (used by the dev importer's v1-format reader). */
    public static com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot parse JSON", e);
        }
    }
}
