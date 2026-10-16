package trex.v2.hub.api;

/**
 * The sign-in body (V2-SPEC.md §9.5): a declared active user id and a password. Strict like every
 * other typed request — an unknown field is a 400, not silently ignored.
 */
public record LoginRequest(String user, String password) {}
