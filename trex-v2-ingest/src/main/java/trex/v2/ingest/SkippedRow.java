package trex.v2.ingest;

/** A row the parser deliberately did not turn into a fact (a pending authorisation, or blank noise). */
public record SkippedRow(String file, int line, String reason, String raw) {}
