package trex.v2.core.derive;

import trex.v2.core.Fact;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.Registry;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where each account stood before trex saw anything (V2-PROPOSAL.md §11.3, §6.10). The opening is
 * derived <b>backwards</b> — the latest balance minus every amount since — because a statement does
 * not record intra-day order, and anchoring forward was a measured $13,661 coin toss on the real
 * journal. The forward figure is still computed so the difference is reported as a confidence gap
 * (transactions the bank's running balance knows about and the journal does not), never hidden.
 * A declared account opens on its first attestation; a <b>clearing</b> account (a closed
 * counterparty with no facts) opens at {@code closing − Σ movements}, so its derived balance lands
 * on the declared closing.
 */
public final class Opening {

    public record PerAccount(String accountRef, String currency, boolean declared, long latestBalance,
                             long backwardOpening, long forwardOpening, long gap,
                             LocalDate openedAt, boolean clearing) {}

    private Opening() {}

    public static List<PerAccount> of(List<Fact> currentFacts, Registry registry) {
        return of(currentFacts, registry, List.of());
    }

    /**
     * @param transfers the derived pairs; a clearing pair's real leg is the movement that computed
     *                  the clearing account's opening (§6.10)
     */
    public static List<PerAccount> of(List<Fact> currentFacts, Registry registry, List<TransferRow> transfers) {
        Map<String, List<Fact>> byAccount = new TreeMap<>();
        for (Fact f : currentFacts) {
            byAccount.computeIfAbsent(f.accountRef(), k -> new ArrayList<>()).add(f);
        }
        List<PerAccount> out = new ArrayList<>();
        byAccount.forEach((ref, facts) -> {
            Account account = registry.account(ref);
            List<Fact> sorted = facts.stream()
                .sorted(Comparator.comparing(Fact::date).thenComparingLong(Fact::n))
                .toList();
            out.add(account.balanceSource() == BalanceSource.DECLARED
                ? declared(ref, account.currency(), sorted)
                : statement(ref, account.currency(), sorted));
        });

        // A clearing account holds no facts: opening = declared closing − Σ movements. The
        // movement is the clearing side of each pair, the opposite of the real leg's amount.
        Map<String, Fact> factById = new HashMap<>();
        for (Fact f : currentFacts) {
            factById.put(f.externalId(), f);
        }
        Map<String, Long> realSum = new TreeMap<>();
        Map<String, LocalDate> earliest = new TreeMap<>();
        for (TransferRow t : transfers) {
            if (t.clearingAccount() == null) {
                continue;
            }
            String realId = t.clearingAccount().equals(t.fromLeg()) ? t.toLeg() : t.fromLeg();
            Fact real = factById.get(realId);
            if (real == null) {
                continue;
            }
            realSum.merge(t.clearingAccount(), real.amount(), Long::sum);
            earliest.merge(t.clearingAccount(), real.date(), (a, b) -> a.isBefore(b) ? a : b);
        }
        for (Account account : registry.accounts().values()) {
            if (!account.clearing()) {
                continue;
            }
            long opening = account.closingBalance() + realSum.getOrDefault(account.ref(), 0L);
            LocalDate openedAt = earliest.getOrDefault(account.ref(), account.closedAt());
            out.add(new PerAccount(account.ref(), account.currency(), false, account.closingBalance(),
                opening, opening, 0, openedAt, true));
        }
        out.sort(Comparator.comparing(PerAccount::accountRef));
        return out;
    }

    private static PerAccount statement(String ref, String currency, List<Fact> sorted) {
        long sum = sorted.stream().mapToLong(Fact::amount).sum();
        long latest = sorted.getLast().balance();
        long backward = latest - sum;
        Fact first = sorted.getFirst();
        long forward = first.balance() - first.amount();
        return new PerAccount(ref, currency, false, latest, backward, forward, backward - forward,
            first.date(), false);
    }

    private static PerAccount declared(String ref, String currency, List<Fact> sorted) {
        long sumBefore = 0;
        long opening = 0;
        long latest = 0;
        boolean seenAttestation = false;
        long sumAfter = 0;
        for (Fact f : sorted) {
            if (f.amount() == 0) {
                if (!seenAttestation) {
                    opening = f.balance() - sumBefore;
                    seenAttestation = true;
                } else {
                    sumAfter += 0;   // attestations are anchors, not movements
                }
                latest = f.balance();
            } else if (seenAttestation) {
                sumAfter += f.amount();
            } else {
                sumBefore += f.amount();
            }
        }
        long gap = seenAttestation ? latest - (opening + sumAfter) : 0;
        java.time.LocalDate openedAt = sorted.getFirst().date();
        return new PerAccount(ref, currency, true, latest, opening, opening, gap, openedAt, false);
    }
}
