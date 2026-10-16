package trex.v2.hub.auth;

import java.nio.file.Path;

/**
 * The hub's sign-in state (V2-SPEC.md §9.5): the loaded credential file plus the session codec. A
 * {@code null} {@link Auth} is "off" — the hub serves everything exactly as it did before sign-in
 * existed (A-7). It resolves the session user from a request's {@code Cookie} header; the
 * declared-and-active check for login reads {@code users.yaml} through the hub's refdata, not here.
 */
public final class Auth {

    private final AuthFile file;
    private final Session session;

    private Auth(AuthFile file) {
        this.file = file;
        this.session = new Session(file.sessionSecret());
    }

    /** Load the credential file; any failure is a startup error (V2-SPEC.md §9.5). */
    public static Auth load(Path file) {
        return new Auth(AuthFile.load(file));
    }

    /** Wrap an already-loaded file (used by tests and by {@link #load}). */
    public static Auth of(AuthFile file) {
        return new Auth(file);
    }

    public AuthFile file() {
        return file;
    }

    public Session session() {
        return session;
    }

    /** True when the password reproduces the stored hash for that user. */
    public boolean verifyCredentials(String user, String password) {
        return file.verify(user, password);
    }

    /** The signed-in user for a request's {@code Cookie} header, or {@code null} when anonymous. */
    public Session.Principal resolve(String cookieHeader, long nowEpochSeconds) {
        return session.verify(cookieValue(cookieHeader), nowEpochSeconds);
    }

    /** The {@code trex_session} value from a {@code Cookie} header, or {@code null} when absent. */
    private static String cookieValue(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        for (String part : header.split(";")) {
            String pair = part.trim();
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(Session.COOKIE_NAME)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }
}
