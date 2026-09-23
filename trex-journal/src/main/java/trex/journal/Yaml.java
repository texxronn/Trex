package trex.journal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The one mapper for config files, shared by the sequencer and every journal consumer. SPEC §1, §6.
 * Config is YAML; the journal is JSONL and never goes through here ({@link Json}).
 * <p>
 * Strict in both directions: an unknown key and a duplicated key are both errors, so a typo
 * or a pasted-twice block fails at startup rather than being silently dropped or last-wins.
 */
public final class Yaml {

    private static final ObjectMapper MAPPER = YAMLMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .build();

    private Yaml() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Read one config file into {@code type}, with the file name in front of any failure. */
    public static <T> T read(Path file, Class<T> type) {
        try {
            return MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), type);
        } catch (java.io.FileNotFoundException | java.nio.file.NoSuchFileException e) {
            throw new IllegalArgumentException("missing config file: " + file, e);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalArgumentException(file.getFileName() + ": " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
