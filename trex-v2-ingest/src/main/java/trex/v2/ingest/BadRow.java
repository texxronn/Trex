package trex.v2.ingest;

/** A row the whole-file validation rejected: nothing is sent until every row is clean. */
public record BadRow(String file, int line, String field, String value, String reason) {}
