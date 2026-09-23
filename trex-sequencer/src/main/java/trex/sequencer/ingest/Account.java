package trex.sequencer.ingest;

/** Registry entry (accounts.yaml). SPEC §6. */
public record Account(String ref, String currency, String fireflyAccountId) {}
