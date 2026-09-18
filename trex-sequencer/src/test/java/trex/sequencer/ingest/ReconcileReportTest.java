package trex.sequencer.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.Candidate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static trex.sequencer.ingest.Harness.c;

/**
 * SPEC §3.5 {@code GET /reconcile}: the §7 test 6 algorithm over the published snapshot.
 * The algorithm itself is covered by {@link ReconciliationTest}; this covers the report.
 */
class ReconcileReportTest {

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

    private static ReconcileReport.Account account(ReconcileReport report, String ref) {
        return report.accounts().stream()
            .filter(a -> a.accountRef().equals(ref))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no account " + ref + " in " + report.accounts()));
    }

    @Test
    void emptyJournalIsVacuouslyOk() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            ReconcileReport report = h.sequencer().reconcile();
            assertTrue(report.ok());
            assertEquals(List.of(), report.accounts());
            assertEquals(0, report.n());
            assertEquals(0, report.offset());
        }
    }

    @Test
    void wholeStatementBalancesAndIsPinnedToTheJournalHead() {
        List<Candidate> rows = new ArrayList<>(statement());
        Collections.shuffle(rows, new Random(7));
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            h.submit(rows.toArray(Candidate[]::new));
            ReconcileReport report = h.sequencer().reconcile();

            assertTrue(report.ok());
            assertEquals(h.sequencer().view().highWaterN(), report.n());
            assertEquals(h.sequencer().view().headOffset(), report.offset());

            ReconcileReport.Account ing = account(report, "ing-savings");
            assertTrue(ing.reconcilable());
            assertTrue(ing.balances());
            assertEquals(100000, ing.opening());
            assertEquals(298049, ing.closing());
            assertEquals(ing.closing() - ing.opening(), ing.sum());
            assertTrue(account(report, "cba-everyday").balances());
        }
    }

    @Test
    void accountsAreSortedByRef() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            h.submit(statement().toArray(Candidate[]::new));
            List<String> refs = h.sequencer().reconcile().accounts().stream()
                .map(ReconcileReport.Account::accountRef)
                .toList();
            assertEquals(List.of("cba-everyday", "ing-savings"), refs);
        }
    }

    @Test
    void missingRowIsReportedUnreconcilableAndNeverGuessed() {
        List<Candidate> rows = new ArrayList<>(statement());
        rows.remove(2);   // drop a leg: the balance chain no longer joins up
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            h.submit(rows.toArray(Candidate[]::new));
            ReconcileReport report = h.sequencer().reconcile();

            assertFalse(report.ok());
            ReconcileReport.Account ing = account(report, "ing-savings");
            assertFalse(ing.reconcilable());
            assertFalse(ing.balances());
            // Never guesses an opening or a closing it cannot derive (SPEC §7 test 6).
            assertEquals(0, ing.opening());
            assertEquals(0, ing.closing());
            // The other account is unaffected.
            assertTrue(account(report, "cba-everyday").balances());
        }
    }

    @Test
    void reconcileAppendsNothing() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            h.submit(statement().toArray(Candidate[]::new));
            long before = h.sequencer().view().highWaterN();
            int lines = h.lines().size();

            h.sequencer().reconcile();
            h.sequencer().reconcile();

            assertEquals(before, h.sequencer().view().highWaterN());
            assertEquals(lines, h.lines().size());
        }
    }

    @Test
    void transferLinesAreExcludedSoAMatchedTransferStillBalances() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            // Both legs of an internal transfer in one batch: the sequencer appends a
            // TRANSFER line, which must not be counted as a third leg.
            h.submit(
                c("row-2", "ing-savings", "2026-06-01", -50000, "Fast Transfer to CBA", 50000, null),
                c("row-3", "cba-everyday", "2026-06-01", 50000, "Fast Transfer from ING", 50000, null));
            ReconcileReport report = h.sequencer().reconcile();
            assertTrue(report.ok(), () -> "transfer legs should still reconcile: " + report.accounts());
            assertEquals(2, report.accounts().size());
        }
    }
}
