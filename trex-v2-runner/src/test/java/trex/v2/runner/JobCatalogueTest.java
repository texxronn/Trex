package trex.v2.runner;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.log.EvidenceStore;
import trex.v2.log.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobCatalogueTest {

    @Test
    void egressPlanArgvAndApplyGate(@TempDir Path dir) throws Exception {
        RunnerConfig locked = config(dir, false);
        StagingStore staging = new StagingStore(dir.resolve("staging"));
        JobSpec egress = JobCatalogue.of(locked, StatementsMap.load(dir), staging).get("egress-firefly");

        List<Step> plan = egress.steps().build(mode("plan"));
        assertEquals(1, plan.size());
        assertTrue(plan.get(0).argv().contains("--plan"));
        assertTrue(plan.get(0).argv().contains("http://hub:8090"));
        assertTrue(plan.get(0).argv().contains("http://firefly:8081"));

        assertThrows(IllegalArgumentException.class, () -> egress.steps().build(mode("bogus")));
        assertThrows(IllegalArgumentException.class, () -> egress.steps().build(mode("apply")));

        RunnerConfig unlocked = config(dir, true);
        JobSpec apply = JobCatalogue.of(unlocked, StatementsMap.load(dir), staging).get("egress-firefly");
        assertTrue(apply.steps().build(mode("apply")).get(0).argv().contains("--apply"));
    }

    // ---- re-read evidence (QOL_Improvements.md §4) ------------------------------------------

    @Test
    void reparseArgvPreviewAndApplyGate(@TempDir Path dir) throws Exception {
        StagingStore staging = new StagingStore(dir.resolve("staging"));
        String id = new EvidenceStore(dir.resolve("evidence"))
            .put("date,amount\n2026-01-02,-10\n".getBytes(StandardCharsets.UTF_8));

        JobSpec locked = JobCatalogue.of(config(dir, false), StatementsMap.load(dir), staging).get("reparse");
        List<Step> preview = locked.steps().build(reparse(id, "preview"));
        assertEquals(1, preview.size());
        assertEquals(List.of("ingest", "--reparse", id,
            "--source-type", "ing-csv", "--account", "ing-salary",
            "--evidence", dir.resolve("evidence").toString(),
            "--journal", dir.resolve("journal.jsonl").toString(),
            "--sequencer-url", "http://sequencer:8080"), preview.get(0).argv());
        assertEquals(preview.get(0).argv(), locked.steps().build(reparse(id, null)).get(0).argv(),
            "the mode defaults to preview");

        assertThrows(IllegalArgumentException.class, () -> locked.steps().build(reparse(id, "bogus")));
        assertThrows(IllegalArgumentException.class, () -> locked.steps().build(reparse(id, "apply")),
            "apply is refused without --allow-apply");

        JobSpec unlocked = JobCatalogue.of(config(dir, true), StatementsMap.load(dir), staging).get("reparse");
        List<String> applied = unlocked.steps().build(reparse(id, "apply")).get(0).argv();
        assertEquals("--apply", applied.get(applied.size() - 1));

        String unknown = "sha256:" + "0".repeat(64);
        IllegalArgumentException noEvidence = assertThrows(IllegalArgumentException.class,
            () -> locked.steps().build(reparse(unknown, "preview")));
        assertTrue(noEvidence.getMessage().contains("no such evidence"), noEvidence.getMessage());

        assertThrows(IllegalArgumentException.class,
            () -> locked.steps().build(reparse(id, "preview").put("sourceType", "nope")));
        assertThrows(IllegalArgumentException.class,
            () -> locked.steps().build(reparse(id, "preview").put("account", "")));
    }

    private static ObjectNode reparse(String evidence, String mode) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("evidence", evidence).put("sourceType", "ing-csv").put("account", "ing-salary");
        if (mode != null) {
            node.put("mode", mode);
        }
        return node;
    }

    // ---- the inbox sweep (V2-REVIEW-FIXES-PLAN.md §9) ---------------------------------------

    private static final long LATER = System.currentTimeMillis() + 10 * JobCatalogue.SETTLE_MS;

    private static JobSpec inbox(Path dir, StagingStore staging, long now) throws Exception {
        Files.writeString(dir.resolve("statements.yaml"), """
            files:
              - { match: "ING_*.csv", sourceType: ing-csv, account: ing-salary }
            """);
        return JobCatalogue.ingestInbox(config(dir, false), StatementsMap.load(dir), staging, () -> now);
    }

    @Test
    void inboxSweepIngestsMatchedFilesAndFilesThemByExitCode(@TempDir Path dir) throws Exception {
        StagingStore staging = new StagingStore(dir.resolve("staging"));
        StagingStore.Staged ok = staging.put("a".getBytes(StandardCharsets.UTF_8), "ING_Salary.csv");
        StagingStore.Staged bad = staging.put("b".getBytes(StandardCharsets.UTF_8), "ING_Orange.csv");
        StagingStore.Staged flaky = staging.put("c".getBytes(StandardCharsets.UTF_8), "ING_Loan.csv");

        List<Step> steps = inbox(dir, staging, LATER).steps().build(Json.mapper().createObjectNode());
        assertEquals(List.of("ING_Loan.csv", "ING_Orange.csv", "ING_Salary.csv"),
            steps.stream().map(Step::label).sorted().toList());
        Map<String, Step> byLabel = steps.stream().collect(java.util.stream.Collectors.toMap(Step::label, s -> s));
        assertTrue(byLabel.get("ING_Salary.csv").argv().contains("ing-salary"), "the map names the account");

        byLabel.get("ING_Salary.csv").after().accept(0);
        byLabel.get("ING_Orange.csv").after().accept(1);
        byLabel.get("ING_Loan.csv").after().accept(2);

        Map<String, String> state = staging.list().stream()
            .collect(java.util.stream.Collectors.toMap(StagingStore.Staged::name, StagingStore.Staged::state));
        assertEquals("done", state.get(ok.name()));
        assertEquals("failed", state.get(bad.name()), "bad rows are kept, out of the next sweep");
        assertEquals("staged", state.get(flaky.name()), "a transport failure is retried next sweep");
    }

    @Test
    void inboxSweepLeavesUnmatchedAndUnsettledFiles(@TempDir Path dir) throws Exception {
        StagingStore staging = new StagingStore(dir.resolve("staging"));
        staging.put("x".getBytes(StandardCharsets.UTF_8), "unknown-bank.csv");
        NothingToDo none = assertThrows(NothingToDo.class,
            () -> inbox(dir, staging, LATER).steps().build(Json.mapper().createObjectNode()));
        assertTrue(none.getMessage().contains("need a type"), none.getMessage());

        staging.put("y".getBytes(StandardCharsets.UTF_8), "ING_Salary.csv");
        assertThrows(NothingToDo.class,
            () -> inbox(dir, staging, System.currentTimeMillis()).steps().build(Json.mapper().createObjectNode()),
            "a file still being copied in is left for the next sweep");
    }

    @Test
    void anEmptyInboxIsNothingToDo(@TempDir Path dir) throws Exception {
        StagingStore staging = new StagingStore(dir.resolve("staging"));
        assertThrows(NothingToDo.class,
            () -> inbox(dir, staging, LATER).steps().build(Json.mapper().createObjectNode()));
    }

    @Test
    void ingestUsesMappingAndStagedPath(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("statements.yaml"), """
            files:
              - { match: "Salary_Account.csv", sourceType: ing-csv, account: ing-salary }
            """);
        RunnerConfig config = config(dir, true);
        StagingStore staging = new StagingStore(dir.resolve("staging"));
        StagingStore.Staged staged = staging.put("x".getBytes(StandardCharsets.UTF_8), "Salary_Account.csv");

        JobSpec ingest = JobCatalogue.of(config, StatementsMap.load(dir), staging).get("ingest");
        ObjectNode params = Json.mapper().createObjectNode();
        ArrayNode items = params.putArray("items");
        items.addObject().put("file", staged.name());

        List<Step> steps = ingest.steps().build(params);
        assertEquals(1, steps.size());
        assertEquals("Salary_Account.csv", steps.get(0).label());
        List<String> argv = steps.get(0).argv();
        assertTrue(argv.contains("ing-csv"));
        assertTrue(argv.contains("ing-salary"));
        assertTrue(argv.contains("--source-archive"), argv.toString());
        assertTrue(argv.get(argv.size() - 1).endsWith(staged.name()));
    }

    @Test
    void ingestRefusesWhenNoAccountAndNoMapping(@TempDir Path dir) throws Exception {
        RunnerConfig config = config(dir, true);
        StagingStore staging = new StagingStore(dir.resolve("staging"));
        StagingStore.Staged staged = staging.put("x".getBytes(StandardCharsets.UTF_8), "Unknown.csv");
        JobSpec ingest = JobCatalogue.of(config, StatementsMap.load(dir), staging).get("ingest");
        ObjectNode params = Json.mapper().createObjectNode();
        params.putArray("items").addObject().put("file", staged.name()).put("sourceType", "ing-csv");
        assertThrows(IllegalArgumentException.class, () -> ingest.steps().build(params));
    }

    private static ObjectNode mode(String mode) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("mode", mode);
        return node;
    }

    private static RunnerConfig config(Path dir, boolean allowApply) {
        return new RunnerConfig("127.0.0.1", 0, dir, dir.resolve("statements"),
            dir.resolve("staging"), dir.resolve("evidence"), dir.resolve("archive"),
            dir.resolve("journal.jsonl"),
            "http://sequencer:8080", "http://hub:8090", "http://firefly:8081",
            dir.resolve("firefly.yaml"), null, allowApply, Duration.ofMinutes(10));
    }
}
