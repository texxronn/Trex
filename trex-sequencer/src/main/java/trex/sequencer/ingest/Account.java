package trex.sequencer.ingest;

/** Registry entry (accounts.toml). SPEC §6. */
public record Account(String ref, String format, String currency, String fireflyAccountId) {}
