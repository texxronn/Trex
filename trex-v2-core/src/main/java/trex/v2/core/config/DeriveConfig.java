package trex.v2.core.config;

/**
 * Everything {@code derive()} needs besides the log and {@code asOf} (V2-PROPOSAL.md §9.1, §9.9 P3):
 * the registry, the category ruleset and the transfer rules, plus the three revision strings that
 * stamp outputs — never inside a state hash (§9.5).
 *
 * <p>{@code configRevision} hashes every file that feeds derivation (refdata, categories,
 * transfers, accounts). The loader computes it; this record only carries it.
 */
public record DeriveConfig(Registry registry, RuleSet categories, TransferRules transfers,
                           Profiles profiles, String configRevision) {

    /** Stamps the derivation; bump when derive's semantics change (a silent reflow, §9.5). */
    public static final String DERIVE_VERSION = "derive/12";

    /** Stamps the state-hash algorithm; old hashes are incomparable across a bump (§9.4). */
    public static final String HASH_VERSION = "statehash/4";

    public DeriveConfig {
        if (configRevision == null || configRevision.isBlank()) {
            throw new IllegalArgumentException("configRevision is required");
        }
        if (profiles == null) {
            throw new IllegalArgumentException("profiles are required (use Profiles.empty())");
        }
    }
}
