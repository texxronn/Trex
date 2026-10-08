package trex.v2.hub.api;

import java.util.List;

/**
 * The reference data the hub serves (V2-PROPOSAL.md §6.6): accounts and users from config, the
 * declared categories, and the three revision stamps. Config, not index, is the source — a person
 * or an account cannot live only in a database that is disposable.
 */
public record RefdataResponse(List<AccountJson> accounts, List<UserJson> users, List<String> categories,
                              String configRevision, String deriveVersion, String hashVersion) {

    public record AccountJson(String ref, String currency, String balanceSource, int settlementWindowDays,
                              String chipColor) {}

    public record UserJson(String id, String name, boolean active, String cadence) {}
}
