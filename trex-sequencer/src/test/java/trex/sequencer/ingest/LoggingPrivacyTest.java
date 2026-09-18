package trex.sequencer.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static trex.sequencer.ingest.Harness.c;

/**
 * SPEC §1: journal lines are financial data, so log statements carry externalId, n, states
 * and counts — never rawDescription, description or an amount. This is easy to break by
 * adding one convenient log line later, so it is asserted rather than left to review.
 *
 * <p>slf4j-simple resolves {@code System.err} per write, so replacing it here captures
 * output. The level of the logger under test is pinned in the surefire configuration, so
 * the test does not depend on when SimpleLogger initialises.
 */
class LoggingPrivacyTest {

    @TempDir
    Path dir;

    /** Distinctive enough that a substring match cannot succeed by accident. */
    private static final String SECRET_DESCRIPTION = "ZZQUUX-PRIVATE-MERCHANT-42";
    private static final long SECRET_AMOUNT = -987654;

    private String captureStderr(Runnable work) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            work.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void batchLoggingCarriesCountsButNeverDescriptionsOrAmounts() {
        String logged = captureStderr(() -> {
            try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
                h.submit(c("row-2", "ing-savings", "2026-06-01", SECRET_AMOUNT,
                    SECRET_DESCRIPTION, 12345, null));
                h.sequencer().reconcile();
            }
        });

        // The log line is actually being produced — otherwise the assertions below are vacuous.
        assertTrue(logged.contains("candidate batch"),
            () -> "expected the batch summary to be logged, got: " + logged);
        assertTrue(logged.contains("outcomes"), () -> "expected per-kind counts, got: " + logged);

        assertFalse(logged.contains(SECRET_DESCRIPTION),
            () -> "a raw description reached the log: " + logged);
        assertFalse(logged.contains(String.valueOf(SECRET_AMOUNT)),
            () -> "an amount reached the log: " + logged);
        assertFalse(logged.contains(String.valueOf(Math.abs(SECRET_AMOUNT))),
            () -> "an amount reached the log: " + logged);
        // The reconcile line reports the verdict and a count, never opening/closing/sum.
        assertFalse(logged.contains("12345"), () -> "a balance reached the log: " + logged);
    }

    @Test
    void decisionLoggingAlsoOmitsDescriptionsAndAmounts() {
        String logged = captureStderr(() -> {
            try (Harness h = new Harness(dir.resolve("d.jsonl"))) {
                var response = h.submit(c("row-2", "ing-savings", "2026-06-01", SECRET_AMOUNT,
                    "Fast Transfer to CBA " + SECRET_DESCRIPTION, 12345, null));
                String heldId = Harness.id(response.results().getFirst());
                h.decide(DecisionInput.markExternal("d-1", heldId, "resolved by hand"));
            }
        });

        assertTrue(logged.contains("decision batch"),
            () -> "expected the decision summary to be logged, got: " + logged);
        assertFalse(logged.contains(SECRET_DESCRIPTION),
            () -> "a raw description reached the log: " + logged);
        assertFalse(logged.contains(String.valueOf(Math.abs(SECRET_AMOUNT))),
            () -> "an amount reached the log: " + logged);
    }
}
