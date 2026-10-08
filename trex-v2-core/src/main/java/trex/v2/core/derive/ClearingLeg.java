package trex.v2.core.derive;

import java.time.LocalDate;

/**
 * A derived clearing leg (V2-PROPOSAL.md §6.10): the counterpart of a real leg on a clearing account,
 * materialised so a clearing transfer has two concrete legs and per-account queries are complete.
 *
 * <p>It is **never a fact**: it carries no evidence, is never a decision target, and is not in the
 * journal. {@code legId} is reserved synthetic ({@code clr|…}); {@code balance} is derived
 * ({@code opening + Σ movements}) so it lands on the account's declared closing. The account side is
 * still carried on the {@link TransferRow} ({@code clearingAccount}); this row just makes it visible.
 */
public record ClearingLeg(String transferId, String legId, String accountRef, LocalDate date,
                          long amount, long balance, String description, String realLegId, long n) {}
