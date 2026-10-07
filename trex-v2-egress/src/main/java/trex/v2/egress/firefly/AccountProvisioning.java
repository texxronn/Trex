package trex.v2.egress.firefly;

import trex.v2.egress.hub.HubClient.OpeningState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Creating the Firefly accounts {@code firefly.yaml} maps but the instance lacks (V2-PROPOSAL.md
 * §11.3). Opt-in only: a mistyped name would otherwise create an eleventh account and post a year
 * into it. The opening is the backward figure, and a liability is seeded negative — seeding one
 * positive puts the account out by exactly twice the figure (a measured mistake).
 */
public final class AccountProvisioning {

    private AccountProvisioning() {}

    public static long seedOpening(AccountMap.Kind kind, long backwardOpening) {
        return kind == AccountMap.Kind.LIABILITY ? -Math.abs(backwardOpening) : backwardOpening;
    }

    /** Creates the missing accounts and returns the refs it created. */
    public static List<String> createMissing(FireflyClient firefly, AccountMap map, AccountMap resolved,
                                             Map<String, OpeningState> openings)
            throws IOException, InterruptedException {
        List<String> created = new ArrayList<>();
        for (Map.Entry<String, AccountMap.Entry> e : map.byRef().entrySet()) {
            AccountMap.Entry current = resolved.get(e.getKey());
            if (current != null && current.id() != null) {
                continue;
            }
            OpeningState opening = openings.get(e.getKey());
            long cents = opening == null ? 0 : seedOpening(e.getValue().kind(), opening.backwardOpening());
            String currency = opening == null ? "AUD" : opening.currency();
            firefly.createAccount(e.getValue().name(), e.getValue().kind(), currency, cents,
                opening == null ? null : opening.openedAt());
            created.add(e.getKey());
        }
        return created;
    }
}
