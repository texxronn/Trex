package trex.v2.hub.api;

import java.util.List;

/**
 * The reference data the hub serves (V2-PROPOSAL.md §6.6): accounts and users from config, the
 * declared categories, the three revision stamps, and the declared mortgage facilities
 * (V2-MORTGAGE-PLAN.md). Config, not index, is the source — a person or an account cannot live
 * only in a database that is disposable.
 */
public record RefdataResponse(List<AccountJson> accounts, List<UserJson> users, List<String> categories,
                              String configRevision, String deriveVersion, String hashVersion,
                              List<MortgageJson> mortgages) {

    public record AccountJson(String ref, String currency, String balanceSource, int settlementWindowDays,
                              String chipColor) {}

    public record UserJson(String id, String name, boolean active, String cadence) {}

    /**
     * The familiar six-argument shape, with no mortgage facilities: callers and tests that predate
     * {@code mortgages.yaml} carry no facilities.
     */
    public RefdataResponse(List<AccountJson> accounts, List<UserJson> users, List<String> categories,
                           String configRevision, String deriveVersion, String hashVersion) {
        this(accounts, users, categories, configRevision, deriveVersion, hashVersion, List.of());
    }

    /**
     * One declared mortgage facility (V2-MORTGAGE-PLAN.md): the offset account, the loan it
     * shelters (when named — the offset→loan link is a declaration, not an inference), its loans,
     * and an optional observed rate for the idle-offset estimate. Set from config; empty when no
     * {@code mortgages.yaml} is declared.
     */
    public record MortgageJson(String name, String offset, String offsetAppliesTo, Double observedRatePct,
                               List<LoanJson> loans) {}

    /** One loan of a facility: its account ref, a label, its two commitment ids, and open/closed state. */
    public record LoanJson(String account, String label, String repaymentCommitment,
                           String interestCommitment, String state) {}
}
