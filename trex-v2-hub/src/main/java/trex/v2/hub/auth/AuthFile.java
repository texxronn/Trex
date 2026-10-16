package trex.v2.hub.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.log.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The credential file (V2-SPEC.md §9.5, V2-AUTH-PLAN.md §4.1): per-user PBKDF2 hashes and one
 * {@code sessionSecret}, at a path outside the repo, {@code 0600}, and written only by
 * {@code trex hub passwd}. It is not a {@code deploy/config} file and config drift must not know
 * about it.
 *
 * <p>Loading is strict: a missing, unreadable, empty-users or malformed file is a startup error,
 * the same posture as an empty {@code users.yaml} — a typo must not silently switch the hub to open.
 * The hub loads one snapshot read-only; {@code passwd} creates and rewrites it.
 */
public final class AuthFile {

    private static final Logger log = LoggerFactory.getLogger(AuthFile.class);

    /** The format this build understands; an unknown version is refused, never guessed. */
    static final int VERSION = 1;

    /** The session-secret length AUTH-PLAN §4.1 names: 32 random bytes. */
    static final int SECRET_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private final String sessionSecret;
    private final Map<String, String> hashes;

    private AuthFile(Path file, String sessionSecret, Map<String, String> hashes) {
        this.file = file;
        this.sessionSecret = sessionSecret;
        this.hashes = hashes;
    }

    /**
     * Load and validate the file. A missing file is {@code IllegalArgumentException("missing config
     * file: …")} via {@link Yaml#read}; a malformed one is an unknown or duplicate key; an empty
     * {@code users} map, a missing secret or a bad hash is refused here.
     */
    public static AuthFile load(Path file) {
        Shape shape = Yaml.read(file, Shape.class);
        if (shape.version() != VERSION) {
            throw new IllegalArgumentException(file + ": unsupported auth version " + shape.version());
        }
        if (shape.sessionSecret() == null || shape.sessionSecret().isBlank()) {
            throw new IllegalArgumentException(file + ": sessionSecret is required");
        }
        int secretBytes = secretLength(file, shape.sessionSecret());
        if (secretBytes < SECRET_BYTES) {
            throw new IllegalArgumentException(file + ": sessionSecret is " + secretBytes
                + " bytes, but the spec requires at least " + SECRET_BYTES
                + " (V2-SPEC.md §9.5)");
        }
        Map<String, UserEntry> users = shape.users();
        if (users == null || users.isEmpty()) {
            throw new IllegalArgumentException(file
                + ": no users; an empty credential file is a startup error, never an open hub"
                + " (V2-SPEC.md §9.5)");
        }
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Map.Entry<String, UserEntry> entry : users.entrySet()) {
            String id = entry.getKey();
            String pbkdf2 = entry.getValue() == null ? null : entry.getValue().pbkdf2();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException(file + ": a user key must not be blank");
            }
            if (!PasswordHash.isValid(pbkdf2)) {
                // A clear message, because a weakened or malformed hash is a startup error and the
                // operator has to know what the spec's shape is (V2-SPEC.md §9.5).
                throw new IllegalArgumentException(file + ": user '" + id + "' has no valid pbkdf2 hash"
                    + " (sha256, at least " + PasswordHash.ITERATIONS + " iterations, a salt of at least "
                    + PasswordHash.SALT_BYTES + " bytes and a key of at least " + PasswordHash.KEY_BYTES
                    + " bytes)");
            }
            hashes.put(id, pbkdf2);
        }
        warnIfLoose(file);
        return new AuthFile(file, shape.sessionSecret(), hashes);
    }

    /** The decoded length of the stored session secret, or a startup error if it is not base64. */
    private static int secretLength(Path file, String secret) {
        try {
            return Base64.getDecoder().decode(secret).length;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file
                + ": sessionSecret is not valid base64 (V2-SPEC.md §9.5)");
        }
    }

    /**
     * A note, not a refusal: a group- or world-accessible credential file is a permissions nit, and
     * turning it into a failed startup would turn that nit into an outage. The spec's {@code 0600} is
     * an install instruction (docs/DEPLOYMENTS.md); this names the file and the fix so it can be
     * followed. A filesystem without POSIX permissions simply has nothing to report.
     */
    private static void warnIfLoose(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            boolean loose = perms.stream().anyMatch(p ->
                p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"));
            if (loose) {
                log.warn("the credential file {} is group- or world-accessible; run `chmod 600 {}`"
                    + " (V2-SPEC.md §9.5)", file, file);
            }
        } catch (UnsupportedOperationException e) {
            // No POSIX permissions on this filesystem; the host chmods the file (DEPLOYMENTS.md).
        } catch (IOException e) {
            // The file was just read, so a stat failure here is not worth failing startup over.
        }
    }

    /** A brand-new credential file with a fresh {@link #newSessionSecret()} and no users yet. */
    public static AuthFile create(Path file) {
        return new AuthFile(file, newSessionSecret(), new LinkedHashMap<>());
    }

    /** 32 random bytes, base64: one secret, generated once when the file is created. */
    static String newSessionSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** The HMAC key the session cookie is signed under; changing it invalidates every session. */
    public String sessionSecret() {
        return sessionSecret;
    }

    public Path file() {
        return file;
    }

    /** The ids that have a hash, in file order. */
    public Set<String> users() {
        return Collections.unmodifiableSet(hashes.keySet());
    }

    public boolean hasHash(String user) {
        return hashes.containsKey(user);
    }

    /** Set or replace one user's hash; the caller has already checked the user is a declared active one. */
    public void setHash(String user, String pbkdf2) {
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("user is required");
        }
        if (!PasswordHash.isValid(pbkdf2)) {
            throw new IllegalArgumentException("a valid pbkdf2 hash is required");
        }
        hashes.put(user, pbkdf2);
    }

    /** True when the password reproduces the stored hash; an unknown user is always false. */
    public boolean verify(String user, String password) {
        String stored = hashes.get(user);
        return stored != null && PasswordHash.verify(password, stored);
    }

    /**
     * Write the file atomically — a temp sibling then a move, the pattern the hub uses for rule
     * edits — so a crash cannot leave a half-written credential file the hub would then refuse to
     * start on. {@code 0600} wherever the filesystem has POSIX permissions; the host chmods it
     * otherwise (docs/DEPLOYMENTS.md).
     */
    public void save() {
        Map<String, UserEntry> users = new LinkedHashMap<>();
        hashes.forEach((id, hash) -> users.put(id, new UserEntry(hash)));
        String yaml;
        try {
            yaml = Yaml.mapper().writeValueAsString(new Shape(VERSION, sessionSecret, users));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException("cannot serialise the credential file", new IOException(e));
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
            restrictToOwner(tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            restrictToOwner(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // A filesystem without POSIX permissions; the host still chmods the file (DEPLOYMENTS).
        } catch (IOException e) {
            // A real failure to tighten the file is not swallowed: the caller must decide, and a
            // credential file that could not be secured should not be reported as written.
            throw new UncheckedIOException("cannot set 0600 on " + file, e);
        }
    }

    // ---- the on-disk shape (V2-SPEC.md §9.5) -------------------------------------------------

    record Shape(int version, String sessionSecret, Map<String, UserEntry> users) {}

    record UserEntry(String pbkdf2) {}
}
