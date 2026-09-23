package trex.core;

import java.time.LocalDate;

/** Identity tier — sealed so the id function is exhaustive and centralized. SPEC §2.3. */
public sealed interface IdentityStrategy permits IdentityStrategy.NaturalKey, IdentityStrategy.ContentHash {

    record NaturalKey(String accountRef, LocalDate date, String receipt) implements IdentityStrategy {}

    record ContentHash(String accountRef, LocalDate date, long amount,
                       String rawDescription, int occ) implements IdentityStrategy {}
}
