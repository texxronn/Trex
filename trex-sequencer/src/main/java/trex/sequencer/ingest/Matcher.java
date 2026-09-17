package trex.sequencer.ingest;

import trex.core.CanonicalEvent;
import trex.core.Ids;
import trex.core.MatchOutcome;
import trex.core.MatchOutcome.AmbiguousTransfer;
import trex.core.MatchOutcome.ExactTransfer;
import trex.core.MatchOutcome.FuzzyTransfer;
import trex.core.MatchOutcome.HeldLeg;
import trex.core.MatchOutcome.NotTransfer;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Predicate;

/** Phase-1 defensive transfer matcher (T1, T3). Pure. SPEC §3.4. */
public final class Matcher {

    private final TransferRules rules;

    public Matcher(TransferRules rules) {
        this.rules = rules;
    }

    /**
     * @param leg            the incoming new leg
     * @param pool           current HELD legs (existing and earlier in this batch)
     * @param transferIdTaken whether a transfer id already exists (collision → review, never a guess)
     */
    public MatchOutcome match(CanonicalEvent leg, List<CanonicalEvent> pool, Predicate<String> transferIdTaken) {
        String id = leg.externalId();
        if (leg.receipt() != null && !leg.receipt().isBlank()) {
            List<CanonicalEvent> t1 = pool.stream()
                .filter(p -> leg.receipt().equals(p.receipt()))
                .filter(p -> !p.accountRef().equals(leg.accountRef()))
                .filter(p -> oppositeSign(leg.amount(), p.amount()))
                .toList();
            if (t1.size() == 1) {
                String transferId = Ids.transferId(leg.receipt());
                return transferIdTaken.test(transferId)
                    ? new AmbiguousTransfer(id, ids(t1))
                    : new ExactTransfer(id, t1.getFirst().externalId(), transferId);
            }
            if (t1.size() > 1) {
                return new AmbiguousTransfer(id, ids(t1));
            }
        }
        if (!rules.isTransferShaped(leg.rawDescription())) {
            return new NotTransfer(id);
        }
        List<CanonicalEvent> t3 = pool.stream()
            .filter(p -> rules.isTransferShaped(p.rawDescription()))
            .filter(p -> Math.abs(p.amount()) == Math.abs(leg.amount()))
            .filter(p -> oppositeSign(leg.amount(), p.amount()))
            .filter(p -> Math.abs(ChronoUnit.DAYS.between(p.date(), leg.date())) <= rules.windowDays())
            .filter(p -> !p.accountRef().equals(leg.accountRef()))
            .filter(p -> p.currency().equals(leg.currency()))
            .toList();
        if (t3.size() == 1) {
            String transferId = Ids.transferId(id, t3.getFirst().externalId());
            return transferIdTaken.test(transferId)
                ? new AmbiguousTransfer(id, ids(t3))
                : new FuzzyTransfer(id, t3.getFirst().externalId(), transferId);
        }
        if (t3.size() > 1) {
            return new AmbiguousTransfer(id, ids(t3));
        }
        return new HeldLeg(id);
    }

    static boolean oppositeSign(long a, long b) {
        return (a < 0 && b > 0) || (a > 0 && b < 0);
    }

    private static List<String> ids(List<CanonicalEvent> legs) {
        return legs.stream().map(CanonicalEvent::externalId).sorted().toList();
    }
}
