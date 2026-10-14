package trex.v2.hub.api;

import java.time.LocalDate;

/**
 * {@code POST /api/cash}: a manual cash fact on a declared wallet (V2-SPEC.md §9.4,
 * V2-CASH-PLAN.md §4.3) — a spend ({@code amount} negative), income (positive) or a wallet count
 * ({@code amount} absent or {@code 0} with {@code balance} the counted value). {@code category} is
 * an optional {@code PIN} applied to the new fact in the same request. {@code receipt} is a
 * client-minted {@code MAN-…} identity ref (the hub mints one when absent) and {@code user} is the
 * acting user who signs the {@code PIN}.
 *
 * <p>{@code amount} and {@code balance} are boxed so an absent field is a validation error, never a
 * silent zero.
 */
public record CashRequest(String account, LocalDate date, Long amount, Long balance,
                          String description, String receipt, String category, String user) {}
