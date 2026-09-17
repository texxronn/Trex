package trex.sequencer.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.Candidate;
import trex.core.state.Reconciliation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static trex.sequencer.ingest.Harness.c;

/** SPEC §7 test 6: Σ amounts == closing − opening, exact long cents, order-independent. */
class ReconciliationTest {

    @TempDir
    Path dir;

    private static List<Candidate> statement() {
        return List.of(
            c("row-2", "ing-savings", "2026-06-01", -1999, "Woolworths", 98001, null),
            c("row-3", "ing-savings", "2026-06-01", -1, "Bank fee", 98000, null),
            c("row-4", "ing-savings", "2026-06-02", 250037, "Salary", 348037, null),
            c("row-5", "ing-savings", "2026-06-03", -50000, "Fast Transfer to CBA", 298037, null),
            c("row-6", "ing-savings", "2026-06-03", 12, "Interest", 298049, null),
            c("row-7", "cba-everyday", "2026-06-03", 50000, "Transfer from ING", 50000, null));
    }

    @Test
    void shuffledStatementReconcilesExactly() {
        List<Candidate> rows = new ArrayList<>(statement());
        Collections.shuffle(rows, new Random(7));
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            h.submit(rows.toArray(Candidate[]::new));
            var results = Reconciliation.reconcile(h.lines());
            var ing = results.get("ing-savings");
            assertTrue(ing.reconcilable());
            assertEquals(100000, ing.opening());
            assertEquals(298049, ing.closing());
            assertEquals(ing.closing() - ing.opening(), ing.sum());
            assertTrue(ing.balances());
            assertTrue(results.get("cba-everyday").balances());
        }
    }

    @Test
    void missingRowIsUnreconcilable() {
        List<Candidate> rows = new ArrayList<>(statement());
        rows.remove(2);
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            h.submit(rows.toArray(Candidate[]::new));
            assertFalse(Reconciliation.reconcile(h.lines()).get("ing-savings").reconcilable());
        }
    }
}
