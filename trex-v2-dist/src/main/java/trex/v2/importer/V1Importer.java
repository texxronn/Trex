package trex.v2.importer;

import com.fasterxml.jackson.databind.JsonNode;
import trex.v2.core.Provenance;
import trex.v2.log.Json;
import trex.v2.sequencer.api.BatchResponse;
import trex.v2.sequencer.api.DecisionBatch;
import trex.v2.sequencer.api.DecisionDraft;
import trex.v2.sequencer.api.FactBatch;
import trex.v2.sequencer.api.FactDraft;
import trex.v2.sequencer.api.RowResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The one-shot v1-format reader (V2-IMPLEMENTATION-PLAN.md P0.7): it maps a v1 journal to v2 facts
 * and decisions by {@code V2-PROPOSAL.md §16}, and submits them through the sequencer, so identity
 * is re-minted by the same rules rather than copied.
 *
 * <p>This is a dev tool, not a migration path. It is the only v1-format reader in the tree and it
 * imports no v1 classes. The rules:
 * <ul>
 *   <li>the first observation of each {@code externalId} (any state) → one fact, derived fields
 *       dropped;</li>
 *   <li>a {@code TRANSFER} line → one {@code PAIR} (actor {@code migrated});</li>
 *   <li>a re-append to {@code EXTERNAL} with a comment → {@code MARK_EXTERNAL};</li>
 *   <li>a re-append that clears a {@code POTENTIAL_DUP} flag → {@code DISMISS(POTENTIAL_DUP)};</li>
 *   <li>a {@code corrects} link → {@code SUPERSEDE};</li>
 *   <li>a non-null {@code pins.yaml} entry → one {@code PIN} (the operator's choice).</li>
 * </ul>
 * Automatic {@code MATCHED}/{@code HELD}/{@code REVIEW}/{@code EXTERNAL} states and duplicate
 * flags are not imported: they re-derive under the same config. A fact whose re-minted id differs
 * from v1's is reported, never silently accepted.
 */
public final class V1Importer {

    /** Where imported lines go; the HTTP client and a direct in-process sequencer both fit. */
    public interface Sink {
        BatchResponse facts(FactBatch batch);
        BatchResponse decisions(DecisionBatch batch);
    }

    public record Report(int facts, int pairs, int markExternal, int dismiss, int supersede, int pins,
                         List<String> identityMismatches) {}

    private static final int DECISION_CHUNK = 500;

    private V1Importer() {}

    public static Report importJournal(Path v1Journal, Path pinsFile, Sink sink) throws IOException {
        List<V1Line> lines = read(v1Journal);

        Map<String, V1Line> firstById = new TreeMap<>();
        for (V1Line line : lines) {
            if (line.typeHint().equals("TRANSFER")) {
                continue;
            }
            firstById.merge(line.externalId(), line, (a, b) -> a.n() <= b.n() ? a : b);
        }

        List<String> mismatches = new ArrayList<>();
        int facts = submitFacts(firstById, sink, mismatches);

        int pairs = 0;
        int markExternal = 0;
        int dismiss = 0;
        int supersede = 0;
        List<DecisionDraft> decisions = new ArrayList<>();
        Map<String, V1Line> previousById = new LinkedHashMap<>();
        for (V1Line line : lines) {
            if (line.typeHint().equals("TRANSFER")) {
                if (line.legIds().size() >= 2) {
                    decisions.add(new D("PAIR", "migrated").at(line.ingestedAt()).comment(line.comment())
                        .legs(line.legIds().get(0), line.legIds().get(1)).build());
                    pairs++;
                }
                continue;
            }
            if (line.corrects() != null) {
                decisions.add(new D("SUPERSEDE", "migrated").at(line.ingestedAt())
                    .fromId(line.corrects()).toId(line.externalId())
                    .reason("migrated from v1 corrects").build());
                supersede++;
            }
            V1Line previous = previousById.put(line.externalId(), line);
            if (previous == null) {
                continue; // the first observation is the fact
            }
            if (line.comment() != null && line.state().equals("EXTERNAL")) {
                decisions.add(new D("MARK_EXTERNAL", "migrated").at(line.ingestedAt())
                    .externalId(line.externalId()).comment(line.comment()).build());
                markExternal++;
            } else if (line.comment() != null && previous.flags().contains("POTENTIAL_DUP")
                && !line.flags().contains("POTENTIAL_DUP")) {
                decisions.add(new D("DISMISS", "migrated").at(line.ingestedAt()).item("POTENTIAL_DUP")
                    .ids(List.of(line.externalId())).comment(line.comment()).build());
                dismiss++;
            }
        }
        decisions.sort(Comparator.comparing(d -> d.at()));
        submitDecisions(decisions, sink);

        int pins = importPins(pinsFile, firstById.keySet(), sink);
        return new Report(facts, pairs, markExternal, dismiss, supersede, pins, mismatches);
    }

    private static int submitFacts(Map<String, V1Line> firstById, Sink sink, List<String> mismatches) {
        Map<String, List<V1Line>> byDay = new LinkedHashMap<>();
        List<V1Line> ordered = new ArrayList<>(firstById.values());
        ordered.sort(Comparator.comparingLong(V1Line::n));
        for (V1Line line : ordered) {
            byDay.computeIfAbsent(line.accountRef() + '\u0000' + line.date(), k -> new ArrayList<>()).add(line);
        }
        int facts = 0;
        for (List<V1Line> day : byDay.values()) {
            List<FactDraft> drafts = day.stream().map(V1Importer::draft).toList();
            BatchResponse response = sink.facts(new FactBatch(true, drafts));
            for (int i = 0; i < day.size(); i++) {
                RowResult result = response.results().get(i);
                if (result.outcome().equals(RowResult.REJECTED)) {
                    throw new IllegalStateException("v1 journal line rejected: " + result.reason());
                }
                if (!day.get(i).externalId().equals(result.externalId())) {
                    mismatches.add(day.get(i).externalId() + " -> " + result.externalId());
                }
                if (result.outcome().equals(RowResult.APPENDED)) {
                    facts++;
                }
            }
        }
        return facts;
    }

    private static void submitDecisions(List<DecisionDraft> decisions, Sink sink) {
        for (int i = 0; i < decisions.size(); i += DECISION_CHUNK) {
            List<DecisionDraft> chunk = decisions.subList(i, Math.min(decisions.size(), i + DECISION_CHUNK));
            BatchResponse response = sink.decisions(new DecisionBatch(List.copyOf(chunk)));
            response.results().stream()
                .filter(r -> r.outcome().equals(RowResult.REJECTED))
                .findFirst()
                .ifPresent(r -> {
                    throw new IllegalStateException("imported decision rejected: " + r.reason());
                });
        }
    }

    private static int importPins(Path pinsFile, Set<String> knownIds, Sink sink) throws IOException {
        if (pinsFile == null || Files.notExists(pinsFile)) {
            return 0;
        }
        JsonNode pins = trex.v2.log.Yaml.mapper()
            .readTree(Files.readString(pinsFile, StandardCharsets.UTF_8)).path("pins");
        if (!pins.isArray()) {
            return 0;
        }
        List<DecisionDraft> decisions = new ArrayList<>();
        for (JsonNode pin : pins) {
            List<String> ids = new ArrayList<>();
            for (JsonNode id : pin.path("when").path("externalId")) {
                ids.add(id.asText());
            }
            if (ids.isEmpty() || !knownIds.containsAll(ids)) {
                continue; // an orphan pin is skipped, never guessed
            }
            decisions.add(new D("PIN", "migrated").ids(ids).category(pin.path("category").asText())
                .comment(text(pin, "comment")).build());
        }
        submitDecisions(decisions, sink);
        return decisions.size();
    }

    private static FactDraft draft(V1Line line) {
        return new FactDraft(line.accountRef(), line.date(), line.amount(), line.balance(), line.rawDescription(),
            line.receipt(), trex.v2.core.Observation.POSTED, line.sourceType(), line.provenance(), null, null,
            line.ingestedAt());
    }

    private static List<V1Line> read(Path journal) throws IOException {
        List<V1Line> out = new ArrayList<>();
        for (String raw : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
            if (!raw.isBlank()) {
                out.add(parse(Json.readTree(raw)));
            }
        }
        return out;
    }

    private static V1Line parse(JsonNode n) {
        List<String> legIds = new ArrayList<>();
        for (JsonNode id : n.path("legIds")) {
            legIds.add(id.asText());
        }
        List<String> flags = new ArrayList<>();
        for (JsonNode f : n.path("flags")) {
            flags.add(f.asText());
        }
        return new V1Line(
            n.path("n").asLong(),
            n.path("externalId").asText(),
            n.path("accountRef").asText(),
            java.time.LocalDate.parse(n.path("date").asText()),
            n.path("amount").asLong(),
            n.path("balance").asLong(),
            n.path("rawDescription").asText(),
            text(n, "receipt"),
            n.path("typeHint").asText("WITHDRAWAL"),
            legIds,
            text(n, "corrects"),
            n.path("state").asText("EXTERNAL"),
            flags,
            "AUTHORED".equals(n.path("provenance").asText("BANK")) ? Provenance.AUTHORED : Provenance.BANK,
            n.path("sourceType").asText("v1"),
            text(n, "comment"),
            java.time.Instant.parse(n.path("ingestedAt").asText()));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private record V1Line(long n, String externalId, String accountRef, java.time.LocalDate date, long amount,
                          long balance, String rawDescription, String receipt, String typeHint,
                          List<String> legIds, String corrects, String state, List<String> flags,
                          Provenance provenance, String sourceType, String comment,
                          java.time.Instant ingestedAt) {}

    /** Named setters over the flat API DTO, so an action cannot be built with a miscounted field list. */
    private static final class D {
        private final String action;
        private final String actor;
        private java.time.Instant at;
        private String comment;
        private String legA;
        private String legB;
        private String externalId;
        private List<String> externalIds;
        private String item;
        private String category;
        private String fromId;
        private String toId;
        private String reason;

        D(String action, String actor) {
            this.action = action;
            this.actor = actor;
        }

        D at(java.time.Instant v) { at = v; return this; }
        D comment(String v) { comment = v; return this; }
        D legs(String a, String b) { legA = a; legB = b; return this; }
        D externalId(String v) { externalId = v; return this; }
        D ids(List<String> v) { externalIds = v; return this; }
        D item(String v) { item = v; return this; }
        D category(String v) { category = v; return this; }
        D fromId(String v) { fromId = v; return this; }
        D toId(String v) { toId = v; return this; }
        D reason(String v) { reason = v; return this; }

        DecisionDraft build() {
            return new DecisionDraft(action, actor, null, at, comment, legA, legB, externalId, null, null,
                item, externalIds, category, fromId, toId, reason, null,
                null, null, null, null, null);
        }
    }
}
