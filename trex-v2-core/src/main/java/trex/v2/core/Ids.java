package trex.v2.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Which of the two a row mints is decided per batch by {@link #mint}.
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

    /** One row of a batch, as the minting rule sees it. */
    public record Row(String accountRef, LocalDate date, long amount, String rawDescription, String receipt) {}

    /** A minted row: its id and the {@code occ} it claimed. */
    public record Minted(String id, int occ) {}

    /**
     * Mint the ids of one batch, in batch order (§6.1). The sequencer and the re-parse preview both
     * call this, so a preview is exactly what would land.
     *
     * <p>A content-hash row takes {@code occ} = the count of earlier rows in the batch with the same
     * {@code (account, date, amount, rawDescription)}. A receipt row keeps its natural key with
     * {@code occ 0} — unless its receipt is <b>shared on that day by rows of different content</b>.
     * ING prints one receipt on a purchase, its international fee and the fee rebate (measured: 58
     * such groups, 112 rows, all {@code ing-csv}); one natural key for them would merge three
     * transactions into one id. Such rows are minted exactly like receipt-less rows; the
     * {@code receipt} field itself is kept on the fact. Identical rows sharing a receipt are still one
     * row. Day-atomic batching means the batch always holds the whole day, so the rule is complete.
     */
    public static List<Minted> mint(List<Row> rows) {
        Map<String, Set<String>> contentsByReceipt = new HashMap<>();
        for (Row r : rows) {
            if (hasReceipt(r)) {
                contentsByReceipt.computeIfAbsent(receiptKey(r), k -> new HashSet<>())
                    .add(r.amount() + "\u0000" + r.rawDescription());
            }
        }
        Map<String, Integer> contentCounts = new HashMap<>();
        List<Minted> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            if (hasReceipt(r) && contentsByReceipt.get(receiptKey(r)).size() == 1) {
                out.add(new Minted(naturalKey(r.accountRef(), r.date(), r.receipt()), 0));
                continue;
            }
            String key = r.accountRef() + '\u0000' + r.date() + '\u0000' + r.amount() + '\u0000' + r.rawDescription();
            int occ = contentCounts.getOrDefault(key, 0);
            contentCounts.put(key, occ + 1);
            out.add(new Minted(contentHash(r.accountRef(), r.date(), r.amount(), r.rawDescription(), occ), occ));
        }
        return out;
    }

    private static boolean hasReceipt(Row r) {
        return r.receipt() != null && !r.receipt().isBlank();
    }

    private static String receiptKey(Row r) {
        return r.accountRef() + '\u0000' + r.date() + '\u0000' + r.receipt();
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
