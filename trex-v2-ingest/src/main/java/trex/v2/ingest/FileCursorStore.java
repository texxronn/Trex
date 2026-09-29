package trex.v2.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** A cursor per source in one small file each; durable and offline. */
public final class FileCursorStore implements CursorStore {

    private final Path root;

    public FileCursorStore(Path root) {
        this.root = root;
    }

    @Override
    public String get(String source) {
        Path file = file(source);
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).strip() : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void put(String source, String cursor) {
        if (cursor == null) {
            return;
        }
        try {
            Files.createDirectories(root);
            Files.writeString(file(source), cursor, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(String source) {
        return root.resolve(source.replaceAll("[^A-Za-z0-9._-]", "_") + ".cursor");
    }
}
