package trex.v2.runner;

import com.fasterxml.jackson.databind.JsonNode;
import trex.v2.ingest.Adapters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The jobs the runner offers (V2-PROPOSAL.md §5.5). Phase 1 is deliberately two: the Firefly pass
 * and ingest. Every job is a validated builder over the existing subcommands — no new logic lives
 * here, and nothing here reads the log or the index.
 */
public final class JobCatalogue {

    private JobCatalogue() {}

    public static Map<String, JobSpec> of(RunnerConfig config, StatementsMap statements, StagingStore staging) {
        Map<String, JobSpec> jobs = new LinkedHashMap<>();
        jobs.put("egress-firefly", egressFirefly(config));
        jobs.put("ingest", ingest(config, statements, staging));
        jobs.put("ingest-inbox", ingestInbox(config, statements, staging, System::currentTimeMillis));
        jobs.put("journal-snapshot", journalSnapshot(config));
        jobs.put("stream", stream(config, staging));
        return jobs;
    }

    static JobSpec stream(RunnerConfig config, StagingStore staging) {
        List<JobParam> params = List.of(
            JobParam.choice("mode", List.of("export", "ingest"), "export writes a stream; ingest appends one"),
            JobParam.of("file", "string", false, "ingest: the stream file in staging"),
            JobParam.of("out", "string", false, "export: the .jsonl.gz to write in staging"),
            JobParam.of("env", "string", false, "ingest: re-stamp every line's env"));
        return new JobSpec("stream", "Stream export/ingest",
            "Promote the log as a stream (V2-PROPOSAL.md §14.1).", "write", params, p -> {
                String mode = p.path("mode").asText("export");
                if ("export".equals(mode)) {
                    if (config.journal() == null) {
                        throw new IllegalArgumentException("--journal is not configured");
                    }
                    String out = text(p, "out");
                    if (out == null) {
                        throw new IllegalArgumentException("export needs an out file name");
                    }
                    return List.of(new Step("stream export", List.of("stream", "export",
                        "--journal", config.journal().toString(),
                        "--config", config.configDir().toString(),
                        "--out", resolveInside(config.stagingDir(), out).toString())));
                }
                require(config.sequencerUrl(), "--sequencer-url is not configured");
                String file = text(p, "file");
                if (file == null) {
                    throw new IllegalArgumentException("ingest needs a file name");
                }
                Path source;
                try {
                    source = staging.resolve(file);
                } catch (IOException | IllegalArgumentException e) {
                    throw new IllegalArgumentException(e.getMessage());
                }
                List<String> argv = new ArrayList<>(List.of("stream", "ingest",
                    "--file", source.toString(),
                    "--config", config.configDir().toString(),
                    "--sequencer-url", config.sequencerUrl(),
                    "--evidence", config.evidenceDir().toString()));
                String env = text(p, "env");
                if (env != null) {
                    argv.add("--env");
                    argv.add(env);
                }
                return List.of(new Step("stream ingest", argv));
            });
    }

    static JobSpec journalSnapshot(RunnerConfig config) {
        return new JobSpec("journal-snapshot", "Snapshot journal",
            "Write a dated gzip copy of the journal to the archive (V2-PROPOSAL.md §12.6).",
            "read", List.of(), p -> {
                require(config.sequencerUrl(), "--sequencer-url is not configured");
                return List.of(new Step("snapshot",
                    List.of("snapshot", "--sequencer-url", config.sequencerUrl())));
            });
    }

    static JobSpec egressFirefly(RunnerConfig config) {
        List<JobParam> params = List.of(
            JobParam.choice("mode", List.of("plan", "verify", "apply"),
                "plan reads, verify rebuilds from Firefly and expects no diff, apply writes"),
            JobParam.of("seedCategories", "bool", false, "create the declared categories; fill empty notes only"),
            JobParam.of("createMissingAccounts", "bool", false, "create Firefly accounts this config maps"),
            JobParam.of("removeOrphans", "bool", false, "delete groups whose unit is no longer projectable"));
        return new JobSpec("egress-firefly", "Firefly egress",
            "Plan, verify or apply the one-way Firefly projection (V2-PROPOSAL.md §11).",
            "write", params, p -> {
                String mode = p.path("mode").asText("plan");
                if (!List.of("plan", "verify", "apply").contains(mode)) {
                    throw new IllegalArgumentException("mode must be plan, verify or apply");
                }
                if ("apply".equals(mode) && !config.allowApply()) {
                    throw new IllegalArgumentException("apply is disabled; start the runner with --allow-apply");
                }
                require(config.hubUrl(), "--hub-url is not configured");
                require(config.fireflyUrl(), "--firefly-url is not configured");
                if (config.accountsFile() == null) {
                    throw new IllegalArgumentException("--accounts is not configured");
                }
                List<String> argv = new ArrayList<>(List.of("egress", "firefly",
                    "--hub-url", config.hubUrl(),
                    "--firefly-url", config.fireflyUrl(),
                    "--accounts", config.accountsFile().toString()));
                if (p.path("seedCategories").asBoolean(false)) {
                    argv.add("--seed-categories");
                    argv.add("--config");
                    argv.add(config.configDir().toString());
                }
                if (p.path("createMissingAccounts").asBoolean(false)) {
                    argv.add("--create-missing-accounts");
                }
                if (p.path("removeOrphans").asBoolean(false)) {
                    argv.add("--remove-orphans");
                }
                argv.add("--" + mode);
                return List.of(new Step("egress firefly --" + mode, argv));
            });
    }

    static JobSpec ingest(RunnerConfig config, StatementsMap statements, StagingStore staging) {
        List<JobParam> params = List.of(JobParam.of("items", "items", true,
            "one entry per file: {file, sourceType, account, source: staging|statements}"));
        return new JobSpec("ingest", "Ingest",
            "Parse one or more statements into facts (V2-PROPOSAL.md §12); one child per file.",
            "write", params, p -> {
                require(config.sequencerUrl(), "--sequencer-url is not configured");
                JsonNode items = p.path("items");
                if (!items.isArray() || items.isEmpty()) {
                    throw new IllegalArgumentException("items must be a non-empty array");
                }
                List<Step> steps = new ArrayList<>();
                for (JsonNode item : items) {
                    steps.add(ingestStep(config, statements, staging, item));
                }
                return steps;
            });
    }

    /** A file in the inbox must sit unmodified this long before a sweep takes it. */
    static final long SETTLE_MS = 30_000;

    /**
     * The drop-folder sweep (V2-REVIEW-FIXES-PLAN.md §9): the {@code ingest} job with its items
     * resolved from {@code statements.yaml} instead of picked by hand, and each file filed by its
     * exit code — {@code 0} to {@code done/}, {@code 1} (bad rows) or {@code 3} (rejected) to
     * {@code failed/}, anything else (transport, usage) left for the next sweep. Re-ingesting a file
     * appends nothing, so a crash between the ingest and the move is harmless. A file the map does
     * not name stays in the inbox for a person to type.
     */
    static JobSpec ingestInbox(RunnerConfig config, StatementsMap statements, StagingStore staging,
                               java.util.function.LongSupplier clock) {
        return new JobSpec("ingest-inbox", "Ingest the inbox",
            "Ingest every settled staged file that statements.yaml names; file each under done/ or failed/.",
            "write", List.of(), p -> {
                require(config.sequencerUrl(), "--sequencer-url is not configured");
                List<Step> steps = new ArrayList<>();
                int unnamed = 0;
                for (String name : staging.settled(clock.getAsLong(), SETTLE_MS)) {
                    if (statements.resolve(staging.originalOf(name)).isEmpty()) {
                        unnamed++;
                        continue;
                    }
                    com.fasterxml.jackson.databind.node.ObjectNode item =
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                    item.put("file", name);
                    Step ingest = ingestStep(config, statements, staging, item);
                    steps.add(new Step(ingest.label(), ingest.argv(), code -> file(staging, name, code)));
                }
                if (steps.isEmpty()) {
                    throw new NothingToDo(unnamed == 0 ? "the inbox is empty"
                        : unnamed + " file(s) in the inbox need a type and account (statements.yaml)");
                }
                return steps;
            });
    }

    private static void file(StagingStore staging, String name, int code) {
        try {
            if (code == trex.v2.ingest.IngestRunner.OK) {
                staging.markDone(name);
            } else if (code == trex.v2.ingest.IngestRunner.BAD_ROWS || code == trex.v2.ingest.IngestRunner.REJECTED) {
                staging.markFailed(name);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Step ingestStep(RunnerConfig config, StatementsMap statements, StagingStore staging,
                                   JsonNode item) {
        String file = text(item, "file");
        if (file == null) {
            throw new IllegalArgumentException("each item needs a file");
        }
        boolean fromStatements = "statements".equals(text(item, "source"));
        Path path;
        if (fromStatements) {
            if (config.statementsDir() == null) {
                throw new IllegalArgumentException("no statements directory configured");
            }
            path = resolveInside(config.statementsDir(), file);
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("not found: " + file);
            }
        } else {
            try {
                path = staging.resolve(file);
            } catch (IOException | IllegalArgumentException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }
        String sourceType = text(item, "sourceType");
        String account = text(item, "account");
        String mapName = fromStatements ? file : staging.originalOf(file);
        if (sourceType == null || account == null) {
            var entry = statements.resolve(mapName);
            if (sourceType == null) {
                sourceType = entry.map(StatementsMap.Entry::sourceType).orElse(null);
            }
            if (account == null) {
                account = entry.map(StatementsMap.Entry::account).orElse(null);
            }
        }
        if (sourceType == null) {
            throw new IllegalArgumentException("no source type for " + mapName + "; set one");
        }
        if (account == null) {
            throw new IllegalArgumentException("no account for " + mapName + "; set one");
        }
        if (!Adapters.types().contains(sourceType)) {
            throw new IllegalArgumentException("unknown source type '" + sourceType + "'");
        }
        List<String> argv = new ArrayList<>(List.of("ingest",
            "--source-type", sourceType,
            "--account", account,
            "--sequencer-url", config.sequencerUrl(),
            "--evidence", config.evidenceDir().toString()));
        if (config.archive() != null) {
            argv.add("--source-archive");
            argv.add(config.archive().toString());
            argv.add("--source-name");
            argv.add(mapName);
        }
        argv.add(path.toString());
        return new Step(mapName, argv);
    }

    private static Path resolveInside(Path root, String name) {
        if (name.contains("..")) {
            throw new IllegalArgumentException("bad file name: " + name);
        }
        Path resolved = root.resolve(name).normalize();
        if (!resolved.startsWith(root.normalize())) {
            throw new IllegalArgumentException("bad file name: " + name);
        }
        return resolved;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
