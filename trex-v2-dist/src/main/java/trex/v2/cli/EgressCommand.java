package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import trex.v2.core.config.DeriveConfig;
import trex.v2.egress.archive.ArchiveMirror;
import trex.v2.egress.ezb.EzbClient;
import trex.v2.egress.ezb.EzbMap;
import trex.v2.egress.ezb.EzbView;
import trex.v2.egress.firefly.AccountChecks;
import trex.v2.egress.firefly.AccountMap;
import trex.v2.egress.firefly.AccountProvisioning;
import trex.v2.egress.firefly.CategorySeeding;
import trex.v2.egress.firefly.FireflyClient;
import trex.v2.egress.firefly.FireflyEgress;
import trex.v2.egress.firefly.Validate;
import trex.v2.egress.hub.HubClient;
import trex.v2.log.ConfigLoader;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code trex egress archive|firefly|ezb}: project trex outward (V2-PROPOSAL.md §11, §14). Archive is
 * the byte mirror; Firefly and ezbookkeeping are batches, never daemons — {@code --plan}/{@code
 * --verify} are timer-safe and {@code --apply} runs on instruction. Firefly is dormant (§11.1); ezb
 * is the live view (§11.2).
 */
@Command(name = "egress", mixinStandardHelpOptions = true,
    description = "Project trex outward: the archive mirror, the Firefly projection and the "
        + "ezbookkeeping view.",
    subcommands = {EgressCommand.Archive.class, EgressCommand.Firefly.class, EgressCommand.Ezb.class})
public final class EgressCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }

    // ---- archive ----------------------------------------------------------------------------

    @Command(name = "archive", mixinStandardHelpOptions = true,
        description = "Byte mirror of the journal, plus an evidence copy.")
    static final class Archive implements Callable<Integer> {
        @Option(names = "--journal", required = true, description = "The source journal (read-only).")
        Path journal;

        @Option(names = "--archive", required = true, description = "The mirror destination.")
        Path archive;

        @Option(names = "--evidence", description = "Evidence store to copy.")
        Path evidence;

        @Option(names = "--evidence-archive", description = "Evidence mirror destination.")
        Path evidenceArchive;

        @Override
        public Integer call() throws Exception {
            ArchiveMirror.Result result = ArchiveMirror.mirrorJournal(journal, archive);
            System.out.printf("journal mirrored: %d bytes (%s)%n", result.bytes(),
                result.copied() ? "copied/appended" : "already up to date");
            if (evidence != null && evidenceArchive != null) {
                int copied = ArchiveMirror.copyEvidence(evidence, evidenceArchive);
                System.out.printf("evidence copied: %d file(s)%n", copied);
            }
            return 0;
        }
    }

    // ---- firefly ----------------------------------------------------------------------------

    @Command(name = "firefly", mixinStandardHelpOptions = true,
        description = "Firefly plan/apply/verify convergence.")
    static final class Firefly implements Callable<Integer> {

        @Option(names = "--hub-url", required = true, description = "The hub base URL (the only feed).")
        String hubUrl;

        @Option(names = "--firefly-url", required = true, description = "The Firefly base URL.")
        String fireflyUrl;

        @Option(names = "--accounts", required = true, description = "firefly.yaml account mapping.")
        Path accountsFile;

        @Option(names = "--config", description = "Config directory (needed by --seed-categories).")
        Path config;

        @Option(names = "--plan", description = "Show the diff; write nothing.")
        boolean plan;

        @Option(names = "--apply", description = "Execute the diff.")
        boolean apply;

        @Option(names = "--verify", description = "Rebuild state from Firefly, then plan; non-zero if not empty.")
        boolean verify;

        @Option(names = "--validate", description = "Read-only contract check; exit 1 on a Firefly-side edit.")
        boolean validate;

        @Option(names = "--remove-orphans", description = "Delete groups for units no longer projectable.")
        boolean removeOrphans;

        @Option(names = "--seed-categories", description = "Create declared categories; fill empty notes only.")
        boolean seedCategories;

        @Option(names = "--create-missing-accounts", description = "Create Firefly accounts this config maps.")
        boolean createMissingAccounts;

        @Option(names = "--print-accounts", description = "Print the resolved account mapping and exit.")
        boolean printAccounts;

        @Option(names = "--retries", defaultValue = "3", description = "Total tries for transient failures.")
        int retries;

        @Option(names = "--retry-base-ms", defaultValue = "500", description = "First backoff, ms.")
        long retryBaseMs;

        @Option(names = "--retry-max-ms", defaultValue = "30000", description = "Backoff ceiling, ms.")
        long retryMaxMs;

        @Override
        public Integer call() throws Exception {
            String token = System.getenv("FIREFLY_TOKEN");
            if (token == null || token.isBlank()) {
                System.err.println("FIREFLY_TOKEN is required in the environment (never a flag)");
                return 64;
            }
            FireflyEgress.Mode mode = verify ? FireflyEgress.Mode.VERIFY
                : apply ? FireflyEgress.Mode.APPLY : FireflyEgress.Mode.PLAN;
            FireflyClient firefly = new FireflyClient(fireflyUrl, token,
                new FireflyClient.Retry(retries, retryBaseMs, retryMaxMs));
            HubClient hub = new HubClient(hubUrl);
            AccountMap map = AccountMap.load(accountsFile);

            Map<String, FireflyClient.AccountInfo> instance = firefly.accounts();
            Map<String, String> idsByName = new LinkedHashMap<>();
            instance.forEach((name, info) -> idsByName.put(name, info.id()));
            AccountMap resolved = map.resolved(idsByName);

            // Inspect only: --print-accounts lists the mapping as resolved, never provisioning,
            // so a missing ref still shows as "(missing)" and the command writes nothing.
            if (printAccounts) {
                resolved.byRef().forEach((ref, e) -> System.out.printf("  %-24s %-28s %s%n",
                    ref, e.name(), e.id() == null ? "(missing)" : "id " + e.id()));
                return 0;
            }

            // Read-only contract check (D9/R2): classify every Firefly-side edit beside the normal
            // states, write nothing. The names must resolve first, or Projection.of cannot compute
            // what trex expects. Violations exit 1; the informational kinds do not.
            if (validate) {
                List<String> unresolved = resolved.unresolved();
                if (!unresolved.isEmpty()) {
                    System.err.println("firefly.yaml names accounts this instance does not have: "
                        + String.join(", ", unresolved)
                        + ". Fix the name, or pass --create-missing-accounts.");
                    return 1;
                }
                var units = hub.units(0);
                List<Validate.Finding> findings = Validate.check(units, hub.projection(),
                    firefly.inventory(), resolved);
                findings.forEach(f -> System.out.printf("  %-10s %-14s group %-6s %s%n",
                    f.kind(), f.unitId(), f.groupId(), f.detail()));
                java.util.Set<String> violations =
                    java.util.Set.of("MISSING", "UNTAGGED", "TAMPERED", "DRIFT");
                boolean bad = findings.stream().anyMatch(f -> violations.contains(f.kind()));
                System.out.printf("validate: %d finding(s)%n", findings.size());
                return bad ? 1 : 0;
            }

            // Every account we already resolve is checked before any write, so that
            // --create-missing-accounts cannot leave Firefly changed when a mapping disagrees.
            List<String> existingProblems = AccountChecks.problems(resolved, instance, hub.currencyByAccount());
            if (!existingProblems.isEmpty()) {
                System.err.println("firefly.yaml disagrees with the instance; nothing has been written:");
                existingProblems.forEach(p -> System.err.println("  " + p));
                return 1;
            }

            if (createMissingAccounts) {
                resolved = createMissing(firefly, hub, map, idsByName, resolved);
            }
            List<String> unresolved = resolved.unresolved();
            if (!unresolved.isEmpty()) {
                System.err.println("firefly.yaml names accounts this instance does not have: "
                    + String.join(", ", unresolved)
                    + ". Fix the name, or pass --create-missing-accounts.");
                return 1;
            }
            List<String> problems = AccountChecks.problems(resolved, firefly.accounts(), hub.currencyByAccount());
            if (!problems.isEmpty()) {
                System.err.println("firefly.yaml disagrees with the instance; nothing has been written:");
                problems.forEach(p -> System.err.println("  " + p));
                return 1;
            }
            if (seedCategories) {
                seedCategories(firefly);
            }

            FireflyEgress.Outcome outcome = new FireflyEgress(hub, firefly, resolved, mode, removeOrphans,
                System.out, DeriveConfig.DERIVE_VERSION).run();
            System.out.printf("done: %d created, %d re-keyed, %d updated, %d retagged, %d unchanged, "
                    + "%d orphan(s), %d removed, %d of your edits preserved%n",
                outcome.creates(), outcome.rekeys(), outcome.updates(), outcome.retags(), outcome.unchanged(),
                outcome.orphans(), outcome.removed(), outcome.preserved());
            return mode == FireflyEgress.Mode.VERIFY && !outcome.empty() ? 1 : 0;
        }

        private AccountMap createMissing(FireflyClient firefly, HubClient hub, AccountMap map,
                                         Map<String, String> idsByName, AccountMap resolved)
                throws Exception {
            Map<String, HubClient.OpeningState> openings = new LinkedHashMap<>();
            for (HubClient.OpeningState o : hub.opening()) {
                openings.put(o.accountRef(), o);
            }
            List<String> created = AccountProvisioning.createMissing(firefly, map, resolved, openings);
            created.forEach(ref -> System.out.println("created Firefly account for " + ref));
            firefly.accounts().forEach((name, info) -> idsByName.put(name, info.id()));
            return map.resolved(idsByName);
        }

        private void seedCategories(FireflyClient firefly) throws Exception {
            if (config == null) {
                throw new IllegalArgumentException("--seed-categories needs --config");
            }
            var rules = ConfigLoader.load(config).config().categories();
            Map<String, String> comments = new LinkedHashMap<>();
            rules.rules().forEach(r -> comments.putIfAbsent(r.category(), r.comment()));
            CategorySeeding.Result result = CategorySeeding.seed(firefly, rules.declared(), comments);
            System.out.printf("categories seeded: %d created, %d annotated%n",
                result.created(), result.annotated());
        }
    }

    // ---- ezbookkeeping (V2-SPEC.md §11.2) ---------------------------------------------------

    /**
     * The disposable ezbookkeeping view. Simpler than Firefly: no external ids, ownership tags,
     * fingerprints or orphans — every apply clears the transactions and re-imports the whole view.
     * The token is the API token {@code EZB_TOKEN}; the clear uses it alone, so no account password
     * is stored (§11.2).
     */
    @Command(name = "ezb", mixinStandardHelpOptions = true,
        description = "ezbookkeeping view plan/apply/verify.")
    static final class Ezb implements Callable<Integer> {

        @Option(names = "--hub-url", required = true, description = "The hub base URL (the only feed).")
        String hubUrl;

        @Option(names = "--ezb-url", required = true, description = "The ezbookkeeping base URL.")
        String ezbUrl;

        @Option(names = "--accounts", required = true, description = "ezb.yaml account mapping.")
        Path accountsFile;

        @Option(names = "--plan", description = "Show the diff; write nothing.")
        boolean plan;

        @Option(names = "--apply", description = "Clear the view and re-import every unit plus its opening.")
        boolean apply;

        @Option(names = "--verify", description = "Compare each balance against opening + units; non-zero on a diff.")
        boolean verify;

        @Option(names = "--retries", defaultValue = "3", description = "Total tries for transient failures.")
        int retries;

        @Option(names = "--retry-base-ms", defaultValue = "500", description = "First backoff, ms.")
        long retryBaseMs;

        @Option(names = "--retry-max-ms", defaultValue = "30000", description = "Backoff ceiling, ms.")
        long retryMaxMs;

        @Override
        public Integer call() throws Exception {
            String token = System.getenv("EZB_TOKEN");
            if (token == null || token.isBlank()) {
                System.err.println("EZB_TOKEN is required in the environment (never a flag)");
                return 64;
            }
            EzbView.Mode mode = verify ? EzbView.Mode.VERIFY
                : apply ? EzbView.Mode.APPLY : EzbView.Mode.PLAN;
            EzbMap map = EzbMap.load(accountsFile);
            EzbClient ezb = new EzbClient(ezbUrl, token, map.zone(),
                new EzbClient.Retry(retries, retryBaseMs, retryMaxMs));
            HubClient hub = new HubClient(hubUrl);
            // The fast one-call clear needs a login (EZB_USER + EZB_PASSWORD, §11.2); without them
            // the view clears by id and stores no password.
            String ezbUser = System.getenv("EZB_USER");
            String ezbPassword = System.getenv("EZB_PASSWORD");
            EzbClient.Credentials credentials =
                ezbUser != null && !ezbUser.isBlank() && ezbPassword != null && !ezbPassword.isBlank()
                    ? new EzbClient.Credentials(ezbUser, ezbPassword) : null;

            EzbView.Outcome outcome;
            try {
                outcome = new EzbView(hub, ezb, map, System.out, credentials).run(mode);
            } catch (EzbView.Refused refused) {
                System.err.println(refused.getMessage());
                return 1;
            } catch (EzbClient.Unreachable unreachable) {
                System.err.println(unreachable.getMessage());
                return 1;
            }
            return mode == EzbView.Mode.VERIFY && !outcome.mismatches().isEmpty() ? 1 : 0;
        }
    }
}
