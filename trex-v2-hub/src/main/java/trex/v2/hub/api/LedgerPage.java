package trex.v2.hub.api;

import java.util.List;

/** A server-side page of current transactions: the matching total plus the requested window. */
public record LedgerPage(long total, List<LedgerRow> rows) {}
