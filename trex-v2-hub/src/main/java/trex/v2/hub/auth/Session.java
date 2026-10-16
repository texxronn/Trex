package trex.v2.hub.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * The signed session cookie (V2-SPEC.md §9.5, V2-AUTH-PLAN.md §4.3):
 * {@code v1.<user>.<expEpochSeconds>.<base64url(HMAC-SHA256(secret, "v1|user|exp"))>}.
 *
 * <p>It carries only a user and an expiry — it is not a credential store — and the hub keeps no
 * session table: changing the {@code sessionSecret} invalidates every session at once, and that is
 * the whole revocation model. Verification is total: a malformed, tampered, expired or
 * foreign-secret cookie is simply "anonymous", never a response that hints at why.
 */
public final class Session {

    /** The cookie name the browser carries. */
    public static final String COOKIE_NAME = "trex_session";

    /** A-5: 30 days. */
    public static final long LIFETIME_SECONDS = 30L * 24 * 60 * 60;

    /** Past this age a verified cookie is re-issued with a fresh expiry (A-5, sliding on use). */
    public static final long SLIDE_AFTER_SECONDS = LIFETIME_SECONDS / 2;

    private static final String VERSION = "v1";

    private final String secret;

    public Session(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("a session secret is required");
        }
        this.secret = secret;
    }

    /** Sign {@code user} with an absolute expiry, seconds since the epoch. */
    public String sign(String user, long expEpochSeconds) {
        return VERSION + "." + user + "." + expEpochSeconds + "." + mac(user, expEpochSeconds);
    }

    /**
     * Verify a cookie value at {@code nowEpochSeconds}. Returns the user and expiry when the version
     * parses, the expiry is in the future and the MAC matches; otherwise {@code null}.
     */
    public Principal verify(String cookieValue, long nowEpochSeconds) {
        if (cookieValue == null) {
            return null;
        }
        String[] parts = cookieValue.split("\\.", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            return null;
        }
        String user = parts[1];
        if (user.isEmpty() || user.indexOf('|') >= 0) {
            return null;
        }
        long exp;
        try {
            exp = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (exp <= nowEpochSeconds) {
            return null;
        }
        byte[] expected = mac(user, exp).getBytes(StandardCharsets.UTF_8);
        byte[] presented = parts[3].getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, presented)) {
            return null;
        }
        return new Principal(user, exp);
    }

    /** The {@code Set-Cookie} value for a signed-in user: 30 days from {@code nowEpochSeconds}. */
    public String cookie(String user, long nowEpochSeconds) {
        return COOKIE_NAME + "=" + sign(user, nowEpochSeconds + LIFETIME_SECONDS)
            + "; Max-Age=" + LIFETIME_SECONDS + flags();
    }

    /** Clear the cookie: {@code Max-Age=0} with the same attributes, so every browser path forgets it. */
    public static String clearCookie() {
        return COOKIE_NAME + "=; Max-Age=0" + flags();
    }

    private static String flags() {
        // A-6: HttpOnly and SameSite=Lax always; Secure only behind TLS, because over plain HTTP on
        // the LAN a Secure cookie would simply never be sent. `TREX_HUB_SECURE_COOKIE` is truthy for
        // `1` or `true` (case-insensitive) — the host sets it when a TLS proxy terminates in front
        // (docs/DEPLOYMENTS.md).
        return "; Path=/; HttpOnly; SameSite=Lax" + (secureCookie() ? "; Secure" : "");
    }

    /** Whether to add {@code Secure}; see {@link #flags()}. */
    static boolean secureCookie() {
        String raw = System.getenv("TREX_HUB_SECURE_COOKIE");
        return raw != null && ("1".equals(raw.trim()) || "true".equalsIgnoreCase(raw.trim()));
    }

    /** The base64url HMAC of {@code "v1|user|exp"} under the session secret. */
    private String mac(String user, long expEpochSeconds) {
        String payload = VERSION + "|" + user + "|" + expEpochSeconds;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable in this JDK", e);
        }
    }

    /** A verified session: whose, and until when. */
    public record Principal(String user, long expiresAt) {
        /** True when the cookie is past half-life and should be re-issued (A-5, sliding). */
        public boolean shouldSlide(long nowEpochSeconds) {
            return expiresAt - nowEpochSeconds < SLIDE_AFTER_SECONDS;
        }
    }
}
