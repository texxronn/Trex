package trex.v2.core.derive;

import java.time.YearMonth;

/**
 * One row of the derived {@code cash_month} table (V2-CASH-PLAN.md §4.4, V2-SPEC.md §9.4): a
 * declared wallet's cash flows in one calendar month and the signed figure nobody recorded.
 *
 * <p>{@code inFromBank}/{@code outToBank} are the magnitudes of the synthetic wallet legs in and
 * out; {@code recordedOut}/{@code recordedIn} are the magnitudes of the current manual facts out
 * and in (a count is an ATTESTATION and a {@code noop} is excluded). {@code heldOpen} is the latest
 * count strictly before the month, {@code heldClose} the latest count at or before its end, both
 * null before the first count; {@code heldDelta} is their difference and is {@code 0} when either
 * is null (D-4, the first count only sets a baseline). {@code unrecorded} is the §2 formula and
 * carries the sign: {@code > 0} cash left the wallet unrecorded, {@code < 0} cash appeared
 * unrecorded, {@code = 0} everything is accounted for. {@code open} marks the month {@code asOf}
 * falls in (D-5); every other month is closed.
 */
public record CashMonth(String accountRef, YearMonth month, long inFromBank, long outToBank,
                        long recordedOut, long recordedIn, Long heldOpen, Long heldClose,
                        long heldDelta, long unrecorded, boolean open) {}
