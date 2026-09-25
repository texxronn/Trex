package trex.core.account;

import trex.core.BalanceSource;

/**
 * Registry entry (accounts.yaml). SPEC §6.
 * <p>
 * No Firefly field. The mapping from a {@code ref} to a Firefly account lives in the egress's own
 * config (§5.8), because the sequencer has no business knowing what a Firefly account is — and a
 * second egress would otherwise add a column here too. The {@code ref} is part of identity for
 * receipt-keyed rows (§2.4), so it is permanent; everything downstream is a label.
 */
public record Account(String ref, String currency, BalanceSource balanceSource) {}
