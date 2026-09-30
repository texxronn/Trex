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
        return jobs;
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
