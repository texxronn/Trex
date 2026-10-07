package trex.v2.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * Identity functions (V2-PROPOSAL.md §8.3). FROZEN: the canonical string formats and the hashing
 * are the identity contract; v2 does not touch SPEC.md §2.4.
 *
 * <ul>
 *   <li>Natural key: {@code nk|accountRef|date(ISO)|receipt}; the date is in the key because
 *       receipts recur.</li>
 *   <li>Content hash: {@code ch|accountRef|date(ISO)|amount|rawDescription|occ}; the raw
 *       description is verbatim, untrimmed, exactly as the source published it.</li>
 *   <li>SHA-256, UTF-8, lowercase, first 16 hex chars. 64-bit truncation is accepted; the
 *       collision probability at 10^6 rows is ~2.7e-8 — the trade is explicit.</li>
 * </ul>
 */
public final class Ids {

    private static final int ID_HEX_CHARS = 16;

    private Ids() {}

    /** Natural-key id: a row that carries a non-blank receipt. */
    public static String naturalKey(String accountRef, LocalDate date, String receipt) {
        return sha256Hex16("nk|" + accountRef + "|" + date + "|" + receipt);
    }

    /** Content-hash id: a row without a receipt, distinguished within a day by {@code occ}. */
    public static String contentHash(String accountRef, LocalDate date, long amount, String rawDescription, int occ) {
        return sha256Hex16("ch|" + accountRef + "|" + date + "|" + amount + "|" + rawDescription + "|" + occ);
    }

    /**
     * The id an incoming row mints: the natural key when it has a receipt, else the content hash
     * with its assigned {@code occ} (§6.1).
     */
    public static String externalId(String accountRef, LocalDate date, long amount, String rawDescription,
                                    String receipt, int occ) {
        return receipt != null && !receipt.isBlank()
            ? naturalKey(accountRef, date, receipt)
            : contentHash(accountRef, date, amount, rawDescription, occ);
    }

    /** T1 transfer id: a shared receipt. */
    public static String transferId(String receipt) {
        return "TRF-" + receipt;
    }

    /** T2/T3 transfer id: order-independent hash of the two leg chain roots. */
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
