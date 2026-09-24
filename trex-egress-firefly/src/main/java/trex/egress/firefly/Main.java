package trex.egress.firefly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code trex-egress-firefly --gateway-url <url> --firefly-url <url> --accounts <firefly.yaml>
 * --cache <path> [--once] [--dry-run] [--print-accounts] [--verify] [--poll-seconds 60]}
 * <p>
 * Projects resolved units into Firefly III (SPEC §5.8). A batch you run, not a daemon you forget:
 * the gate is the feature, because half-tuned categories should not reach Firefly while you are
 * mid-edit.
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

    @Option(names = "--once", description = "One pass, then exit.")
    private boolean once = true;

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
            printAccounts(map, live);
            return CommandLine.ExitCode.OK;
        }

        AccountMap resolved = reconcile(map, live);
        if (resolved == null) {
            return 70;
        }

        try (ProjectionCache cache = new ProjectionCache(cacheFile)) {
            FireflyEgress egress = new FireflyEgress(new GatewayClient(gatewayUrl), firefly, cache, resolved, dryRun);
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
    private AccountMap reconcile(AccountMap map, Map<String, FireflyClient.AccountInfo> live) {
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
                .filter(a -> !a.isLiability() || true)
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
        }
        return ok ? resolved : null;
    }

    private void printAccounts(AccountMap map, Map<String, FireflyClient.AccountInfo> live) {
        System.out.printf("%-26s %-30s %-12s %-8s %s%n", "ref", "firefly name", "type", "currency", "id");
        map.byRef().forEach((ref, e) -> {
            FireflyClient.AccountInfo info = live.get(e.name());
            System.out.printf("%-26s %-30s %-12s %-8s %s%n", ref, e.name(),
                info == null ? "(missing)" : info.type(),
                info == null ? "" : String.valueOf(info.currency()),
                info == null ? "—" : info.id());
        });
        System.out.println();
        System.out.println("Firefly accounts with no mapping:");
        live.values().stream()
            .filter(a -> map.byRef().values().stream().noneMatch(e -> e.name().equals(a.name())))
            .sorted(java.util.Comparator.comparing(FireflyClient.AccountInfo::name))
            .forEach(a -> System.out.printf("  %-30s %-12s id=%s%n", a.name(), a.type(), a.id()));
    }
}
