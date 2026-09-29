package trex.v2.core.config;

/**
 * The subject a category rule tests (V2-PROPOSAL.md §9.9.E): a current fact, its cleaned
 * description (a pure function of the raw one) and its amount sign. No category is stored on the
 * transaction; a rule produces one.
 */
public record RuleSubject(String externalId, String accountRef, String rawDescription,
                          String cleanedDescription, long amount) {

    public static RuleSubject of(String externalId, String accountRef, String rawDescription, long amount) {
        return new RuleSubject(externalId, accountRef, rawDescription,
            trex.v2.core.Clean.clean(rawDescription), amount);
    }
}
