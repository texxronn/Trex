package trex.egress.firefly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code trex-egress-firefly --gateway-url <url> --firefly-url <url> --accounts <firefly.yaml>
 * [--cache <path>] [--dry-run] [--verify] [--print-accounts] [--create-missing-accounts]
 * [--seed-categories]}
 * <p>
 * Projects resolved units into Firefly III (SPEC §5.8). <b>A batch you run, once per invocation —
 * there is no daemon and no polling.</b> The gate is the feature: half-tuned categories should not
 * reach Firefly while you are mid-edit, and a background service would project a rule you wrote
 * thirty seconds ago and were about to fix.
 * <p>
 * The API token comes from {@code FIREFLY_TOKEN} in the environment — never a flag, which would be
 * visible in {@code ps} and land in shell history.
 */
@Command(name = "trex-egress-firefly", mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = 64,
    description = "Project resolved transactions into Firefly III.")
public final class Main implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Option(names = "--gateway-url", paramLabel = "<url>",
        description = "trex-gateway (default: ${DEFAULT-VALUE}).")
    private URI gatewayUrl = URI.create("http://127.0.0.1:8085");

    @Option(names = "--firefly-url", paramLabel = "<url>", description = "Firefly III base URL.")
    private URI fireflyUrl;

    @Option(names = "--accounts", paramLabel = "<file>", description = "firefly.yaml (SPEC §5.8).")
    private Path accountsFile;

    @Option(names = "--cache", paramLabel = "<path>",
        description = "Projection cache. Disposable: delete it and the next run rebuilds from Firefly. "
            + "Omit it entirely to keep the cache in memory, which rebuilds every run and leaves no file.")
    private Path cacheFile;

    @Option(names = "--dry-run", description = "Print what would be posted and write nothing.")
    private boolean dryRun;

    @Option(names = "--print-accounts", description = "List Firefly's accounts beside firefly.yaml and exit.")
    private boolean printAccounts;

    @Option(names = "--verify", description = "Rebuild the cache from Firefly before running.")
    private boolean verify;

    @Option(names = "--once", description = "Accepted for symmetry with the other followers; "
        + "this command is always one pass.")
    private boolean once = true;

    @Option(names = "--create-missing-accounts",
        description = "Create any mapped account Firefly does not have, with its opening balance "
            + "from the journal. Off by default: a mistyped name would create an extra account and "
            + "post into it silently.")
    private boolean createMissing;

    @Option(names = "--seed-categories",
        description = "Create the declared categories in Firefly, each carrying its rule comment "
            + "as notes. Idempotent; existing categories are left alone.")
    private boolean seedCategories;

    public static void main(String[] args) {
        int exit = new CommandLine(new Main()).execute(args);
        if (exit != CommandLine.ExitCode.OK) {
            System.exit(exit);
        }
    }

    @Override
    public Integer call() throws Exception {
        String token = System.getenv("FIREFLY_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("FIREFLY_TOKEN is not set. Put it in an environment file (mode 600) "
                + "and source it; never pass a token as a flag.");
            return 64;
        }
        if (fireflyUrl == null || accountsFile == null) {
            System.err.println("--firefly-url and --accounts are required.");
            return 64;
        }

        AccountMap map = AccountMap.load(accountsFile);
        FireflyClient firefly = new FireflyClient(fireflyUrl, token);
        log.info("Firefly III {} at {}", firefly.version(), fireflyUrl);

        Map<String, FireflyClient.AccountInfo> live = firefly.accounts();
        if (printAccounts) {
            printAccounts(map, live, new GatewayClient(gatewayUrl));
            return CommandLine.ExitCode.OK;
        }

        GatewayClient gateway = new GatewayClient(gatewayUrl);
        if (seedCategories) {
            seedCategories(gateway, firefly);
        }
        if (createMissing) {
            live = createMissingAccounts(map, live, gateway, firefly);
        }

        AccountMap resolved = reconcile(map, live, currencies(gateway));
        if (resolved == null) {
            return 70;
        }

        try (ProjectionCache cache = new ProjectionCache(cacheFile)) {
            FireflyEgress egress = new FireflyEgress(gateway, firefly, cache, resolved, dryRun);
            // An in-memory cache starts empty, so it must be rebuilt or the pass would re-post
            // everything. That is the same code path --verify uses, which is why it is trustworthy.
            if (verify || cache.inMemory()) {
                log.info("rebuilt from Firefly: {} transactions{}", egress.rebuildCache(),
                    cache.inMemory() ? " (cache is in memory)" : "");
            }
            FireflyEgress.Outcome outcome = egress.run(System.out);
            System.out.println((dryRun ? "dry run: " : "") + outcome.describe());
            return outcome.failed() == 0 ? CommandLine.ExitCode.OK : 70;
        }
    }

    /**
     * Every mapped account must exist, with the right kind and currency, before anything posts.
     * Posting 1751 transactions into the wrong accounts is not recoverable except one at a time.
     */
    private AccountMap reconcile(AccountMap map, Map<String, FireflyClient.AccountInfo> live,
                                 Map<String, String> currencies) {
        AccountMap resolved = map.resolved(
            live.values().stream().collect(java.util.stream.Collectors.toMap(
                FireflyClient.AccountInfo::name, FireflyClient.AccountInfo::id, (a, b) -> a)));

        List<String> missing = resolved.unresolved();
        boolean ok = true;
        if (!missing.isEmpty()) {
            System.err.println("These refs name Firefly accounts that do not exist:");
            missing.forEach(m -> System.err.println("  " + m));
            // A rename is the usual cause, so name what is unmapped rather than leaving a puzzle.
            List<String> unclaimed = live.values().stream()
                .map(FireflyClient.AccountInfo::name)
                .filter(n -> resolved.byRef().values().stream().noneMatch(e -> e.name().equals(n)))
                .sorted().toList();
            if (!unclaimed.isEmpty()) {
                System.err.println("Firefly has these accounts with no mapping — renamed?");
                unclaimed.forEach(n -> System.err.println("  \"" + n + "\""));
            }
            ok = false;
        }
        for (Map.Entry<String, AccountMap.Entry> e : resolved.byRef().entrySet()) {
            FireflyClient.AccountInfo info = live.get(e.getValue().name());
            if (info == null) {
                continue;
            }
            boolean wantLiability = e.getValue().kind() == AccountMap.Kind.LIABILITY;
            if (info.isLiability() != wantLiability) {
                System.err.printf("  %s: firefly.yaml says %s, Firefly says %s%n",
                    e.getKey(), wantLiability ? "liability" : "asset", info.type());
                ok = false;
            }
            // Currencies must agree or Firefly converts silently and the ledger stops
            // reconciling against the bank, with nothing to show for it.
            if (info.currency() != null && !info.currency().isBlank()
                && !info.currency().equals(currencies.get(e.getKey()))
                && currencies.get(e.getKey()) != null) {
                System.err.printf("  %s: trex stamps %s, Firefly account \"%s\" is %s%n",
                    e.getKey(), currencies.get(e.getKey()), info.name(), info.currency());
                ok = false;
            }
        }
        return ok ? resolved : null;
    }

    /** What trex stamps on each account's lines, so the currency check has something to compare. */
    private Map<String, String> currencies(GatewayClient gateway) {
        Map<String, String> out = new java.util.HashMap<>();
        try {
            for (GatewayClient.Unit u : gateway.since(0).units()) {
                out.putIfAbsent(u.line().accountRef(), u.line().currency());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("could not read currencies from the gateway; skipping that check: {}", e.toString());
        }
        return out;
    }

    /**
     * Create the mapped accounts Firefly does not have, seeding each with the balance it held
     * before trex saw anything — otherwise every balance in Firefly is wrong by a constant and
     * nothing reconciles against the bank.
     */
    private Map<String, FireflyClient.AccountInfo> createMissingAccounts(
            AccountMap map, Map<String, FireflyClient.AccountInfo> live,
            GatewayClient gateway, FireflyClient firefly) throws IOException, InterruptedException {
        Map<String, GatewayClient.Opening> openings = gateway.openingBalances();
        Map<String, String> currencies = currencies(gateway);
        int made = 0;
        for (Map.Entry<String, AccountMap.Entry> e : map.byRef().entrySet()) {
            String ref = e.getKey();
            AccountMap.Entry want = e.getValue();
            if (live.containsKey(want.name())) {
                continue;
            }
            GatewayClient.Opening opening = openings.get(ref);
            String currency = currencies.getOrDefault(ref, "AUD");
            if (dryRun) {
                System.out.printf("  would create %-30s %-10s %-4s opening %s%n", want.name(),
                    want.kind().name().toLowerCase(java.util.Locale.ROOT), currency,
                    opening == null ? "0.00 (no transactions yet)"
                        : Projection.signedAmount(opening.cents()) + " as of " + opening.asOf());
                continue;
            }
            String id = firefly.createAccount(want.name(), want.kind(), currency,
                opening == null ? 0 : opening.cents(), opening == null ? null : opening.asOf());
            System.out.printf("  created %-30s %-10s id=%s%n", want.name(),
                want.kind().name().toLowerCase(java.util.Locale.ROOT), id);
            made++;
        }
        return made > 0 ? firefly.accounts() : live;
    }

    /** Idempotent: Firefly's category names are unique, so only the absent ones are created. */
    private void seedCategories(GatewayClient gateway, FireflyClient firefly)
            throws IOException, InterruptedException {
        Map<String, String> wanted = gateway.categoryNotes();
        Map<String, FireflyClient.CategoryInfo> have = firefly.categories();
        int made = 0;
        int annotated = 0;
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            String name = e.getKey();
            String notes = e.getValue();
            FireflyClient.CategoryInfo existing = have.get(name);
            if (existing == null) {
                made++;
                if (dryRun) {
                    System.out.printf("  create   %-20s %s%n", name, notes == null ? "" : "— " + notes);
                } else {
                    firefly.createCategory(name, notes);
                }
            } else if (notes != null && !notes.isBlank() && !existing.hasNotes()) {
                // Posting a transaction with a category_name auto-creates the category with no
                // notes, so on an instance that was projected before it was seeded, most of them
                // arrive bare. Filling an empty field is safe; overwriting one is not.
                annotated++;
                if (dryRun) {
                    System.out.printf("  annotate %-20s — %s%n", name, notes);
                } else {
                    firefly.setCategoryNotes(existing.id(), notes);
                }
            }
        }
        System.out.printf("  categories: %d declared, %d %s, %d %s, %d already complete%n",
            wanted.size(), made, dryRun ? "to create" : "created",
            annotated, dryRun ? "to annotate" : "annotated",
            wanted.size() - made - annotated);
    }

    private void printAccounts(AccountMap map, Map<String, FireflyClient.AccountInfo> live,
                               GatewayClient gateway) {
        Map<String, GatewayClient.Opening> openings;
        try {
            openings = gateway.openingBalances();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            openings = Map.of();
        }
        System.out.printf("%-26s %-28s %-12s %-5s %-5s %14s  %-12s %s%n",
            "ref", "firefly name", "type", "ccy", "id", "opening", "as of", "note");
        boolean anyGap = false;
        for (Map.Entry<String, AccountMap.Entry> entry : map.byRef().entrySet()) {
            String ref = entry.getKey();
            AccountMap.Entry e = entry.getValue();
            FireflyClient.AccountInfo info = live.get(e.name());
            GatewayClient.Opening opening = openings.get(ref);
            String note = "";
            if (opening != null && !opening.confident()) {
                anyGap = true;
                note = "journal is short " + Projection.signedAmount(opening.gap());
            }
            System.out.printf("%-26s %-28s %-12s %-5s %-5s %14s  %-12s %s%n", ref, e.name(),
                info == null ? "(missing)" : info.type(),
                info == null ? "" : String.valueOf(info.currency()),
                info == null ? "—" : info.id(),
                opening == null ? "—" : Projection.signedAmount(opening.cents()),
                opening == null ? "no transactions" : opening.asOf(), note);
        }
        System.out.println();
        System.out.println("  Openings are derived from the bank's own running balance (SPEC §0.1) and are a");
        System.out.println("  suggestion — check them against a statement. A debt is negative; seeding one");
        System.out.println("  positive puts the account out by exactly twice the figure.");
        if (anyGap) {
            System.out.println();
            System.out.println("  Where a row is marked short, the running balance accounts for transactions the");
            System.out.println("  journal does not hold — usually a partial export. The figure shown makes today's");
            System.out.println("  balance correct while the history stays incomplete; take that opening from a");
            System.out.println("  statement instead, or ingest the missing period first.");
        }
        System.out.println();
        System.out.println("Firefly accounts with no mapping:");
        live.values().stream()
            .filter(a -> map.byRef().values().stream().noneMatch(e -> e.name().equals(a.name())))
            .sorted(java.util.Comparator.comparing(FireflyClient.AccountInfo::name))
            .forEach(a -> System.out.printf("  %-30s %-12s id=%s%n", a.name(), a.type(), a.id()));
    }
}
