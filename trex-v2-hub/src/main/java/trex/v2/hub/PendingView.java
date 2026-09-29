package trex.v2.hub;

import java.time.LocalDate;

/** A pending observation as the index holds it ({@code pending} table); the walk needs its date and state. */
record PendingView(String externalId, String accountRef, LocalDate date, long amount,
                   String settledBy, String state) {}
