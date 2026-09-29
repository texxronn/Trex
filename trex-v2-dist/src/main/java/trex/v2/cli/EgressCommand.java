package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import trex.v2.core.config.DeriveConfig;
import trex.v2.egress.archive.ArchiveMirror;
import trex.v2.egress.firefly.AccountMap;
import trex.v2.egress.firefly.FireflyClient;
import trex.v2.egress.firefly.FireflyEgress;
import trex.v2.egress.hub.HubClient;
import trex.v2.log.ConfigLoader;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code trex egress archive|firefly}: project trex outward (V2-PROPOSAL.md §11, §14). Archive is
 * the byte mirror; Firefly is a batch, never a daemon — {@code --plan}/{@code --verify} are
 * timer-safe and {@code --apply} runs on instruction.
 */
@Command(name = "egress", mixinStandardHelpOptions = true,
    description = "Project trex outward: the archive mirror and the Firefly projection.",
    subcommands = {EgressCommand.Archive.class, EgressCommand.Firefly.class})
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

            Map<String, String> idsByName = new LinkedHashMap<>();
            firefly.accounts().forEach((name, info) -> idsByName.put(name, info.id()));
            AccountMap resolved = map.resolved(idsByName);

            if (createMissingAccounts) {
                resolved = createMissing(firefly, hub, map, idsByName, resolved);
            }
            if (printAccounts) {
                resolved.byRef().forEach((ref, e) -> System.out.printf("  %-24s %-28s %s%n",
                    ref, e.name(), e.id() == null ? "(missing)" : "id " + e.id()));
                return 0;
            }
            List<String> unresolved = resolved.unresolved();
            if (!unresolved.isEmpty()) {
                System.err.println("firefly.yaml names accounts this instance does not have: "
                    + String.join(", ", unresolved)
                    + ". Fix the name, or pass --create-missing-accounts.");
                return 1;
            }
            if (seedCategories) {
                seedCategories(firefly);
            }

            FireflyEgress.Outcome outcome = new FireflyEgress(hub, firefly, resolved, mode, removeOrphans,
                System.out, DeriveConfig.DERIVE_VERSION).run();
            System.out.printf("done: %d created, %d retagged, %d orphan(s), %d removed, %d of your edits preserved%n",
                outcome.creates(), outcome.retags(), outcome.orphans(), outcome.removed(), outcome.preserved());
            return mode == FireflyEgress.Mode.VERIFY && !outcome.empty() ? 1 : 0;
        }

        private AccountMap createMissing(FireflyClient firefly, HubClient hub, AccountMap map,
                                         Map<String, String> idsByName, AccountMap resolved)
                throws Exception {
            Map<String, HubClient.OpeningState> openings = new LinkedHashMap<>();
            for (HubClient.OpeningState o : hub.opening()) {
                openings.put(o.accountRef(), o);
            }
            for (Map.Entry<String, AccountMap.Entry> e : map.byRef().entrySet()) {
                if (resolved.get(e.getKey()).id() != null) {
                    continue;
                }
                HubClient.OpeningState opening = openings.get(e.getKey());
                long cents = opening == null ? 0 : opening.backwardOpening();
                // A liability is seeded negative: seeding one positive puts the account out by
                // exactly twice the figure (a measured mistake).
                if (e.getValue().kind() == AccountMap.Kind.LIABILITY) {
                    cents = -Math.abs(cents);
                }
                String currency = opening == null ? "AUD" : opening.currency();
                String id = firefly.createAccount(e.getValue().name(), e.getValue().kind(), currency, cents, null);
                idsByName.put(e.getValue().name(), id);
                System.out.printf("created account \"%s\" (id %s, opening %d)%n", e.getValue().name(), id, cents);
            }
            return map.resolved(idsByName);
        }

        private void seedCategories(FireflyClient firefly) throws Exception {
            if (config == null) {
                throw new IllegalArgumentException("--seed-categories needs --config");
            }
            var rules = ConfigLoader.load(config).config().categories();
            Map<String, String> comments = new LinkedHashMap<>();
            rules.rules().forEach(r -> comments.putIfAbsent(r.category(), r.comment()));
            Map<String, FireflyClient.CategoryInfo> existing = firefly.categories();
            int created = 0;
            int annotated = 0;
            for (String category : rules.declared()) {
                String notes = comments.get(category);
                FireflyClient.CategoryInfo info = existing.get(category);
                if (info == null) {
                    firefly.createCategory(category, notes);
                    created++;
                } else if (!info.hasNotes() && notes != null && !notes.isBlank()) {
                    firefly.setCategoryNotes(info.id(), notes);
                    annotated++;
                }
            }
            System.out.printf("categories seeded: %d created, %d annotated%n", created, annotated);
        }
    }
}
