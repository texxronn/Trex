package trex.ingest;

/** One value that cannot become part of an exact candidate; any bad row invalidates the whole source (SPEC §4). */
public record BadRow(String file, int line, String column, String value, String reason) {
    @Override
    public String toString() {
        return "%s:%d column %s value '%s': %s".formatted(file, line, column, value, reason);
    }
}
