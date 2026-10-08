package trex.v2.core.derive;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The whole output of {@code derive(facts, decisions, config, asOf)} (V2-PROPOSAL.md §9.1, §9.9):
 * the §7.2 materialisation, pure and deterministic. Every list is ordered, so two runs at the same
 * inputs are byte-comparable.
 *
 * <p>{@code chainResolved} maps any id in the log to the id of its chain's current fact (a
 * superseded id maps to its replacement; a retired id maps to itself, which carries no current
 * fact). Every decision naming an id is applied through this map (§9.8).
 */
public record Derivation(
    Map<String, String> chainResolved,
    List<Supersession> supersession,
    List<CurrentFact> current,
    List<TransferRow> transfers,
    List<PendingRow> pending,
    List<CategoryRow> categories,
    List<PinRow> pins,
    List<NoteRow> notes,
    List<ReviewItem> review,
    List<IneffectiveDecision> ineffective,
    List<Unit> units,
    List<UserAckRow> userAcks) {

    public Derivation {
        chainResolved = Map.copyOf(new TreeMap<>(chainResolved));
        supersession = List.copyOf(supersession);
        current = List.copyOf(current);
        transfers = List.copyOf(transfers);
        pending = List.copyOf(pending);
        categories = List.copyOf(categories);
        pins = List.copyOf(pins);
        notes = List.copyOf(notes);
        review = List.copyOf(review);
        ineffective = List.copyOf(ineffective);
        units = List.copyOf(units);
        userAcks = List.copyOf(userAcks);
    }

    public Optional<CurrentFact> current(String externalId) {
        return current.stream().filter(c -> c.externalId().equals(externalId)).findFirst();
    }

    public Optional<TransferRow> transferOfLeg(String externalId) {
        return transfers.stream()
            .filter(t -> t.fromLeg().equals(externalId) || t.toLeg().equals(externalId))
            .findFirst();
    }

    public Map<String, CurrentFact> currentById() {
        return current.stream().collect(Collectors.toMap(CurrentFact::externalId, Function.identity()));
    }
}
