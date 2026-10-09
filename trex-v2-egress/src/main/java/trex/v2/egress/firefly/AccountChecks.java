package trex.v2.egress.firefly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What startup compares against the instance before anything is written (V2-SPEC.md §11.1). The
 * type is load-bearing — it decides the transaction type, and Firefly refuses a transfer across the
 * asset/liability line. The currency is checked because Firefly converts silently on a mismatch and
 * the ledger simply stops reconciling, with nothing to show for it.
 */
public final class AccountChecks {

    private AccountChecks() {}

    public static List<String> problems(AccountMap resolved, Map<String, FireflyClient.AccountInfo> instanceByName,
                                        Map<String, String> currencyByRef) {
        List<String> out = new ArrayList<>();
        resolved.byRef().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            String ref = e.getKey();
            AccountMap.Entry want = e.getValue();
            FireflyClient.AccountInfo have = instanceByName.get(want.name());
            if (have == null) {
                return;                       // unresolved names are reported separately
            }
            boolean wantLiability = want.kind() == AccountMap.Kind.LIABILITY;
            if (wantLiability != have.isLiability()) {
                out.add(ref + ": firefly.yaml says " + (wantLiability ? "liability" : "asset")
                    + " but Firefly's \"" + want.name() + "\" is " + have.type());
            }
            String currency = currencyByRef.get(ref);
            if (currency != null && have.currency() != null && !currency.equals(have.currency())) {
                out.add(ref + ": accounts.yaml says " + currency + " but Firefly's \"" + want.name()
                    + "\" is " + have.currency());
            }
        });
        return out;
    }
}
