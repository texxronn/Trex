package trex.v2.core.config;

import trex.v2.core.Clean;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Account-scoped role rules (V2-PROPOSAL.md §6.9): a fact whose cleaned description matches a rule
 * declared for its account (or for {@link #ANY_ACCOUNT}) is classified {@code noop} — recorded, but
 * not a posting. This is configuration, never a property of the line: editing it is a reflow, not a
 * re-parse, and the rules ride {@code configRevision} like every other derive input.
 */
public record Profiles(List<Rule> rules) {

    /** One rule: the account it applies to, a case-insensitive find pattern, and the why. */
    public record Rule(String accountRef, Pattern match, String reason) {}

    /** A rule declared for every account. */
    public static final String ANY_ACCOUNT = "*";

    public Profiles {
        rules = List.copyOf(rules);
    }

    public static Profiles empty() {
        return new Profiles(List.of());
    }

    /** True when the raw description matches a noop rule declared for this account. */
    public boolean isNoop(String accountRef, String rawDescription) {
        return reasonFor(accountRef, rawDescription) != null;
    }

    /**
     * The reason of the first noop rule matching this account and cleaned description, or null when
     * none does — the "why" a reconcile result names for a profile-classified exclusion (§6.9).
     */
    public String reasonFor(String accountRef, String rawDescription) {
        if (rules.isEmpty()) {
            return null;
        }
        String cleaned = Clean.clean(rawDescription);
        for (Rule rule : rules) {
            if ((ANY_ACCOUNT.equals(rule.accountRef()) || rule.accountRef().equals(accountRef))
                && rule.match().matcher(cleaned).find()) {
                return rule.reason();
            }
        }
        return null;
    }
}
