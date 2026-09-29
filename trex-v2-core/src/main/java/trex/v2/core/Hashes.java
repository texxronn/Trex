package trex.v2.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 helpers for revisions and state hashes (V2-PROPOSAL.md §9.5). The {@code sha256:} prefix
 * names the algorithm; the hex is the full digest, lowercase.
 */
public final class Hashes {

    private Hashes() {}

    public static String sha256(String canonical) {
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
