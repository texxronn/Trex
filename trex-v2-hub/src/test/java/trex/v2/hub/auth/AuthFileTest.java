package trex.v2.hub.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The credential file (V2-SPEC.md §9.5, V2-AUTH-PLAN.md §4.1): PBKDF2 hashing, the create/save/load
 * round trip, and the startup refusals that keep a typo from silently opening the hub.
 */
class AuthFileTest {

    /** A valid 44-char base64 secret: 32 random bytes, the spec's length (V2-SPEC.md §9.5). */
    private static final String SECRET = "hZ8vQ0m3nJ2wX5tR7kL9pB4cD6fG1sA0eY3uI6oP8qZ=";

    @Test
    void hashVerifiesAndIsSalted() {
        String hash = PasswordHash.hash("correct horse battery staple");
        assertTrue(hash.startsWith("sha256:600000:"), hash);
        assertTrue(PasswordHash.isValid(hash), hash);
        assertTrue(PasswordHash.verify("correct horse battery staple", hash));
        assertFalse(PasswordHash.verify("wrong", hash));
        // A second hash of the same password differs: the salt is fresh each time.
        assertNotEquals(hash, PasswordHash.hash("correct horse battery staple"));
    }

    @Test
    void isValidRejectsGarbage() {
        assertFalse(PasswordHash.isValid(null));
        assertFalse(PasswordHash.isValid("not-a-hash"));
        assertFalse(PasswordHash.isValid("md5:1:aa:bb"));
        assertFalse(PasswordHash.isValid("sha256:0:aaaa:bbbb"));
    }

    @Test
    void createSaveLoadRoundTrip(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("auth.yaml");
        AuthFile created = AuthFile.create(file);
        created.setHash("ron", PasswordHash.hash("hunter2"));
        created.save();

        AuthFile loaded = AuthFile.load(file);
        assertEquals(created.sessionSecret(), loaded.sessionSecret());
        assertEquals(Set.of("ron"), loaded.users());
        assertTrue(loaded.hasHash("ron"));
        assertTrue(loaded.verify("ron", "hunter2"));
        assertFalse(loaded.verify("ron", "nope"));
        assertFalse(loaded.verify("mel", "hunter2"));

        // 0600 where the filesystem carries POSIX permissions (DEPLOYMENTS.md).
        try {
            assertEquals("rw-------",
                java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(file)));
        } catch (UnsupportedOperationException ignored) {
            // A filesystem without POSIX permissions; the host chmods the file itself.
        }
    }

    @Test
    void updatesOneUserAndKeepsTheRest(@TempDir Path dir) {
        Path file = dir.resolve("auth.yaml");
        AuthFile first = AuthFile.create(file);
        first.setHash("ron", PasswordHash.hash("one"));
        first.save();

        AuthFile second = AuthFile.load(file);
        second.setHash("mel", PasswordHash.hash("two"));
        second.save();

        AuthFile loaded = AuthFile.load(file);
        assertEquals(Set.of("ron", "mel"), loaded.users());
        assertTrue(loaded.verify("ron", "one"));
        assertTrue(loaded.verify("mel", "two"));
        assertEquals(first.sessionSecret(), loaded.sessionSecret());
    }

    @Test
    void missingFileIsAnError(@TempDir Path dir) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> AuthFile.load(dir.resolve("absent.yaml")));
        assertTrue(e.getMessage().contains("missing config file"), e.getMessage());
    }

    @Test
    void emptyUsersIsAnError(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("auth.yaml");
        Files.writeString(file, """
            version: 1
            sessionSecret: "%s"
            users: {}
            """.formatted(SECRET));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AuthFile.load(file));
        assertTrue(e.getMessage().contains("no users"), e.getMessage());
    }

    @Test
    void missingSecretIsAnError(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("auth.yaml");
        Files.writeString(file, """
            version: 1
            users:
              ron:
                pbkdf2: "%s"
            """.formatted(PasswordHash.hash("pw")));
        assertThrows(IllegalArgumentException.class, () -> AuthFile.load(file));
    }

    @Test
    void badHashIsAnError(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("auth.yaml");
        Files.writeString(file, """
            version: 1
            sessionSecret: "%s"
            users:
              ron:
                pbkdf2: "not-a-hash"
            """.formatted(SECRET));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AuthFile.load(file));
        assertTrue(e.getMessage().contains("pbkdf2"), e.getMessage());
    }

    @Test
    void aWeakenedHashIsAStartupError(@TempDir Path dir) throws Exception {
        // The spec names 600 000 iterations; a hand-edited count below it is refused at load, so a
        // weakened file can never quietly become an open hub (V2-SPEC.md §9.5).
        String weakened = PasswordHash.hash("pw").replace(":600000:", ":500000:");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> AuthFile.load(validFile(dir, SECRET, weakened)));
        assertTrue(e.getMessage().contains("pbkdf2"), e.getMessage());
    }

    @Test
    void anUnknownAlgorithmIsAStartupError(@TempDir Path dir) throws Exception {
        String md5 = "md5:600000:AAAAAAAAAAAAAAAAAAAAAA==:"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> AuthFile.load(validFile(dir, SECRET, md5)));
        assertTrue(e.getMessage().contains("pbkdf2"), e.getMessage());
    }

    @Test
    void aShortSaltOrKeyIsAStartupError(@TempDir Path dir) throws Exception {
        String shortSalt = "sha256:600000:" + java.util.Base64.getEncoder()
            .encodeToString(new byte[8]) + ":"
            + java.util.Base64.getEncoder().encodeToString(new byte[32]);
        assertThrows(IllegalArgumentException.class,
            () -> AuthFile.load(validFile(dir, SECRET, shortSalt)));

        String shortKey = "sha256:600000:" + java.util.Base64.getEncoder()
            .encodeToString(new byte[16]) + ":"
            + java.util.Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalArgumentException.class,
            () -> AuthFile.load(validFile(dir, SECRET, shortKey)));
    }

    @Test
    void aShortSessionSecretIsAStartupError(@TempDir Path dir) throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> AuthFile.load(validFile(dir, "c2hvcnQ=", PasswordHash.hash("pw"))));
        assertTrue(e.getMessage().contains("sessionSecret"), e.getMessage());

        IllegalArgumentException notBase64 = assertThrows(IllegalArgumentException.class,
            () -> AuthFile.load(validFile(dir, "not base64 at all !!", PasswordHash.hash("pw"))));
        assertTrue(notBase64.getMessage().contains("base64"), notBase64.getMessage());
    }

    @Test
    void aLooseFileWarnsButStillLoads(@TempDir Path dir) throws Exception {
        Path file = validFile(dir, SECRET, PasswordHash.hash("pw"));
        try {
            Files.setPosixFilePermissions(file,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException e) {
            return; // No POSIX permissions on this filesystem: nothing to warn about.
        }
        // A permissions nit is a warning, not an outage: the file still loads and verifies.
        assertTrue(AuthFile.load(file).verify("ron", "pw"));
    }

    /** One user, {@code ron}, with the given secret and pbkdf2 string. */
    private static Path validFile(Path dir, String secret, String pbkdf2) throws Exception {
        Path file = dir.resolve("auth-" + Math.abs(secret.hashCode()) + ".yaml");
        Files.writeString(file, """
            version: 1
            sessionSecret: "%s"
            users:
              ron:
                pbkdf2: "%s"
            """.formatted(secret, pbkdf2));
        return file;
    }

    @Test
    void malformedYamlIsAnError(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("auth.yaml");
        Files.writeString(file, "version: 1\nsessionSecret: \"s\"\nusers:\n  ron:\n    pbkdf2: \"x\"\nstray: true\n");
        assertThrows(IllegalArgumentException.class, () -> AuthFile.load(file));
    }
}
