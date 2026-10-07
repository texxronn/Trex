package trex.v2.sequencer;

import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.LogLine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The sequencer's in-memory fold of the log (V2-PROPOSAL.md §6.3, §6.5): the observation set
 * (dedup), the latest fact per id, the decision {@code n}s and the facts by account/day (for
 * {@code occ}). It is rebuilt from the log on recovery, so dedup survives a restart with no sidecar.
 */
final class SequencerState {

    long headN;
    long headOffset;

    final Set<ObsKey> observations = new HashSet<>();
    final Map<String, Fact> latestById = new HashMap<>();
    final Set<Long> decisionNs = new HashSet<>();
    final Map<String, List<Fact>> factsByDay = new HashMap<>();

    /** The dedup key: the whole observation minus source/operational metadata (§6.5). */
    record ObsKey(String externalId, String accountRef, LocalDate date, long amount,
                  String rawDescription, String receipt, int occ, long balance) {

        static ObsKey of(Fact f) {
            return new ObsKey(f.externalId(), f.accountRef(), f.date(), f.amount(), f.rawDescription(),
                f.receipt(), f.occ(), f.balance());
        }
    }

    static SequencerState fold(Stream<LogLine> lines) {
        SequencerState state = new SequencerState();
        lines.forEach(line -> {
            state.headN = Math.max(state.headN, line.n());
            if (line instanceof Fact fact) {
                state.observe(fact);
            } else if (line instanceof Decision decision) {
                state.decisionNs.add(decision.n());
            }
        });
        return state;
    }

    void observe(Fact fact) {
        observations.add(ObsKey.of(fact));
        latestById.merge(fact.externalId(), fact, (a, b) -> a.n() >= b.n() ? a : b);
        factsByDay.computeIfAbsent(dayKey(fact.accountRef(), fact.date()), k -> new ArrayList<>()).add(fact);
    }

    static String dayKey(String accountRef, LocalDate date) {
        return accountRef + '\u0000' + date;
    }

    /** Occ values already used on a day, from the folded facts, in occ order. */
    List<Fact> dayFacts(String accountRef, LocalDate date) {
        List<Fact> facts = new ArrayList<>(factsByDay.getOrDefault(dayKey(accountRef, date), List.of()));
        facts.sort(java.util.Comparator.comparingInt(Fact::occ));
        return facts;
    }

    Set<Integer> usedOcc(String accountRef, LocalDate date) {
        Set<Integer> used = new HashSet<>();
        for (Fact f : factsByDay.getOrDefault(dayKey(accountRef, date), List.of())) {
            used.add(f.occ());
        }
        return used;
    }

    Map<String, Fact> latestById() {
        return new LinkedHashMap<>(latestById);
    }
}
