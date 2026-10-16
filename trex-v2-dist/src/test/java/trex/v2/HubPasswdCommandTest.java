package trex.v2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.hub.auth.AuthFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code trex hub passwd} (V2-SPEC.md §9.5, V2-AUTH-PLAN.md §4.2, §7 test 6): it creates the file,
 * refuses anyone who is not a declared active user, and never prints a hash.
 */
class HubPasswdCommandTest {

    @Test
    void createsTheFileListsIdsAndNeverPrintsAHash(@TempDir Path dir) throws Exception {
        Path configDir = config(dir, true, true);
        Path auth = dir.resolve("auth.yaml");

        // Two prompts, then a newline per line: no console in a test, so stdin is the source.
        Result set = run("hunter2\nhunter2\n", "hub", "passwd", "--auth", auth.toString(),
            "--config", configDir.toString(), "--user", "ron");
        assertEquals(0, set.exit(), set.err());

        AuthFile loaded = AuthFile.load(auth);
        assertTrue(loaded.verify("ron", "hunter2"));
        assertFalse(loaded.hasHash("mel"));

        Result listed = run("", "hub", "passwd", "--auth", auth.toString(), "--list");
        assertEquals(0, listed.exit(), listed.err());
        assertTrue(listed.out().contains("ron"), listed.out());
        assertFalse(listed.out().contains("sha256"), listed.out());
        assertFalse(listed.out().contains(loaded.sessionSecret()), listed.out());
    }

    @Test
    void listingAnAbsentFileIsEmptyAndCreatesNothing(@TempDir Path dir) throws Exception {
        Path auth = dir.resolve("auth.yaml");
        Result listed = run("", "hub", "passwd", "--auth", auth.toString(), "--list");
        assertEquals(0, listed.exit(), listed.err());
        assertTrue(listed.out().isBlank(), listed.out());
        assertFalse(Files.exists(auth));
    }

    @Test
    void refusesAnInactiveUser(@TempDir Path dir) throws Exception {
        Path configDir = config(dir, true, false);
        Path auth = dir.resolve("auth.yaml");
        Result result = run("hunter2\nhunter2\n", "hub", "passwd", "--auth", auth.toString(),
            "--config", configDir.toString(), "--user", "mel");
        assertNotEquals(0, result.exit());
        assertTrue(result.err().contains("active"), result.err());
        assertFalse(Files.exists(auth), "a refused passwd must not create the file");
    }

    @Test
    void refusesAnUnknownUser(@TempDir Path dir) throws Exception {
        Path configDir = config(dir, true, false);
        Path auth = dir.resolve("auth.yaml");
        Result result = run("hunter2\nhunter2\n", "hub", "passwd", "--auth", auth.toString(),
            "--config", configDir.toString(), "--user", "nobody");
        assertNotEquals(0, result.exit());
        assertTrue(result.err().contains("not declared"), result.err());
    }

    @Test
    void refusesEmptyAndMismatchedPasswords(@TempDir Path dir) throws Exception {
        Path configDir = config(dir, true, false);
        Path auth = dir.resolve("auth.yaml");

        Result empty = run("\n\n", "hub", "passwd", "--auth", auth.toString(),
            "--config", configDir.toString(), "--user", "ron");
        assertNotEquals(0, empty.exit());
        assertTrue(empty.err().contains("empty"), empty.err());

        Result mismatch = run("one\ntwo\n", "hub", "passwd", "--auth", auth.toString(),
            "--config", configDir.toString(), "--user", "ron");
        assertNotEquals(0, mismatch.exit());
        assertTrue(mismatch.err().contains("do not match"), mismatch.err());
        assertFalse(Files.exists(auth));
    }

    // ---- fixture ----------------------------------------------------------------------------

    /** Run the one CLI with stdin/stdout/stderr captured; picocli prints usage to stderr. */
    private static Result run(String stdin, String... args) {
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exit = Main.commandLine().execute(args);
            return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exit, String out, String err) {}

    private static Path config(Path dir, boolean ronActive, boolean melActive) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
                currency: "AUD"
                balanceSource: statement
            """);
        Files.writeString(configDir.resolve("users.yaml"), """
            users:
              - id: "ron"
                name: "Ron"
                active: %s
              - id: "mel"
                name: "Mel"
                active: %s
            """.formatted(ronActive, melActive));
        return configDir;
    }
}
