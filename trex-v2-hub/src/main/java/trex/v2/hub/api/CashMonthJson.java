package trex.v2.hub.api;

/**
 * One {@code cash_month} row served by {@code GET /api/cash/months} (V2-SPEC.md §9.4): a declared
 * wallet's cash flows in one calendar month and the signed unrecorded figure. Field for field the
 * derived record {@link trex.v2.core.derive.CashMonth}, with {@code month} as {@code YYYY-MM} text.
 */
public record CashMonthJson(String accountRef, String month, long inFromBank, long outToBank,
                            long recordedOut, long recordedIn, Long heldOpen, Long heldClose,
                            long heldDelta, long unrecorded, boolean open) {}
