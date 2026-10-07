package trex.v2.hub.api;

/** {@code GET /head}: the index's view of the journal head and how far behind it is. */
public record HeadResponse(long n, long offset, long journalHead, long lagBytes) {}
