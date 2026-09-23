package trex.core;

import trex.core.IdentityStrategy.ContentHash;
import trex.core.IdentityStrategy.NaturalKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Identity functions. SPEC §2.4.
 * FROZEN: the canonical string formats and hashing are the identity contract.
 */
public final class Ids {

    private static final int ID_HEX_CHARS = 16;

    private Ids() {}

    public static String externalId(IdentityStrategy s) {
        String canonical = switch (s) {
            case NaturalKey(String accountRef, var date, String receipt) ->
                "nk|" + accountRef + "|" + date + "|" + receipt;
            case ContentHash(String accountRef, var date, long amount, String rawDescription, int occ) ->
                "ch|" + accountRef + "|" + date + "|" + Long.toString(amount) + "|" + rawDescription + "|" + occ;
        };
        return sha256Hex16(canonical);
    }

    /** NaturalKey if the candidate has a receipt, else ContentHash with its occ. */
    public static IdentityStrategy strategyFor(OccCandidate oc) {
        Candidate c = oc.c();
        if (c.hasReceipt()) {
            return new NaturalKey(c.accountRef(), c.date(), c.receipt());
        }
        return new ContentHash(c.accountRef(), c.date(), c.amount(), c.rawDescription(), oc.occ());
    }

    /** T1 transfer id: shared receipt. */
    public static String transferId(String receipt) {
        return "TRF-" + receipt;
    }

    /** T3 / manual transfer id: order-independent hash of the two leg ids. */
    public static String transferId(String idA, String idB) {
        String min = idA.compareTo(idB) <= 0 ? idA : idB;
        String max = idA.compareTo(idB) <= 0 ? idB : idA;
        return "TRF-" + sha256Hex16("tr|" + min + "|" + max);
    }

    static String sha256Hex16(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, ID_HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
