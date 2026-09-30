package trex.v2.runner;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
            dir.resolve("staging"), dir.resolve("evidence"),
            "http://sequencer:8080", "http://hub:8090", "http://firefly:8081",
            dir.resolve("firefly.yaml"), null, allowApply, Duration.ofMinutes(10));
    }
}
