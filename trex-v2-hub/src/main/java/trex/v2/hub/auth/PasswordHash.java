package trex.v2.hub.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * A per-user password hash (V2-SPEC.md §9.5, V2-AUTH-PLAN.md §4.1): PBKDF2-SHA256, 600 000
 * iterations, a fresh 16-byte salt per hash, stored as {@code sha256:<iterations>:<salt-b64>:<key-b64>}.
 * JDK only — this deliberately does not reuse {@code trex.v2.core.Hashes}, which is a plain SHA-256
 * over revisions, not a password.
 *
 * <p>{@link #verify} compares with {@link MessageDigest#isEqual}, so a wrong password costs the same
 * as a right one and never leaks a matching prefix.
 */
public final class PasswordHash {

    static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    static final String PREFIX = "sha256";
    static final int ITERATIONS = 600_000;
    static final int SALT_BYTES = 16;
    static final int KEY_BYTES = 32;
    private static final int KEY_BITS = KEY_BYTES * 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHash() {}

    /** Hash one password with a fresh random salt. */
    public static String hash(String password) {
        if (password == null) {
            throw new IllegalArgumentException("password is required");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return PREFIX + ":" + ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":"
            + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS, KEY_BITS));
    }

    /** True when {@code password} reproduces the stored hash; anything malformed is simply false. */
    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split(":", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (iterations <= 0 || expected.length == 0) {
            return false;
        }
        byte[] actual = derive(password, salt, iterations, expected.length * 8);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * The shape check the credential loader applies (V2-SPEC.md §9.5): the exact algorithm, a
     * 16-byte salt and a 32-byte key, so a hand-edited file fails at startup rather than at login.
     */
    public static boolean isValid(String stored) {
        if (stored == null) {
            return false;
        }
        String[] parts = stored.split(":", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            if (Integer.parseInt(parts[1]) <= 0) {
                return false;
            }
            return Base64.getDecoder().decode(parts[2]).length == SALT_BYTES
                && Base64.getDecoder().decode(parts[3]).length == KEY_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations, int keyBits) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyBits);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // A standard JDK algorithm: if it is absent, no password can be verified honestly.
            throw new IllegalStateException("PBKDF2 is unavailable in this JDK", e);
        } finally {
            spec.clearPassword();
        }
    }
}
