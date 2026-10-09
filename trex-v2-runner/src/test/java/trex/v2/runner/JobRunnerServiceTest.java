package trex.v2.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class JobRunnerServiceTest {

    @Test
    void runsStepsAndRecordsSuccess(@TempDir Path dir) throws Exception {
        CommandRunner fake = (argv, line, handle) -> {
            line.accept("out:" + argv.get(0));
            return 0;
        };
        JobSpec spec = spec(List.of(new Step("one", List.of("x"))));
        JobRunnerService service = new JobRunnerService(config(dir), Map.of("demo", spec), fake);
        try {
            RunRecord run = service.submit("demo", null);
            awaitTerminal(run);
            assertEquals(RunRecord.State.SUCCEEDED, run.state());
            assertEquals(0, run.exit());
            assertEquals(1, run.stepExits().size());
            assertTrue(run.linesFrom(0).stream().anyMatch(l -> l.equals("out:x")));
            assertNotNull(service.last("demo"));
            assertNull(service.active("demo"));
        } finally {
            service.close();
        }
    }

    @Test
    void failureExitIsCapturedAcrossSteps(@TempDir Path dir) throws Exception {
        CommandRunner fake = (argv, line, handle) -> argv.get(0).equals("bad") ? 3 : 0;
        JobSpec spec = new JobSpec("demo", "Demo", "d", "read", List.of(),
            p -> List.of(new Step("ok", List.of("good")), new Step("bad", List.of("bad"))));
        JobRunnerService service = new JobRunnerService(config(dir), Map.of("demo", spec), fake);
        try {
            RunRecord run = service.submit("demo", null);
            awaitTerminal(run);
            assertEquals(RunRecord.State.FAILED, run.state());
            assertEquals(3, run.exit());
            assertEquals(2, run.stepExits().size());
        } finally {
            service.close();
        }
    }

    @Test
    void afterReceivesEachStepsExitCode(@TempDir Path dir) throws Exception {
        CommandRunner fake = (argv, line, handle) -> argv.get(0).equals("bad") ? 1 : 0;
        List<Integer> seen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        JobSpec spec = new JobSpec("demo", "Demo", "d", "write", List.of(),
            p -> List.of(new Step("ok", List.of("good"), seen::add), new Step("bad", List.of("bad"), seen::add)));
        JobRunnerService service = new JobRunnerService(config(dir), Map.of("demo", spec), fake);
        try {
            awaitTerminal(service.submit("demo", null));
            assertEquals(List.of(0, 1), seen);
        } finally {
            service.close();
        }
    }

    @Test
    void unknownJobIsRejected(@TempDir Path dir) throws Exception {
        JobRunnerService service = new JobRunnerService(config(dir), Map.of(), (argv, line, handle) -> 0);
        try {
            assertThrows(IllegalArgumentException.class, () -> service.submit("nope", null));
        } finally {
            service.close();
        }
    }

    private static JobSpec spec(List<Step> steps) {
        return new JobSpec("demo", "Demo", "d", "read", List.of(), p -> steps);
    }

    private static void awaitTerminal(RunRecord run) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (run.state() == RunRecord.State.SUCCEEDED || run.state() == RunRecord.State.FAILED
                || run.state() == RunRecord.State.CANCELLED) {
                return;
            }
            Thread.sleep(20);
        }
        fail("run did not finish: " + run.state());
    }

    private static RunnerConfig config(Path dir) {
        return new RunnerConfig("127.0.0.1", 0, dir, dir, dir, dir, dir, null, "http://seq", "http://hub",
            "http://ff", dir.resolve("firefly.yaml"), null, true, Duration.ofMinutes(10));
    }
}
