package trex.sequencer.http;

import com.fasterxml.jackson.databind.JsonNode;
import trex.core.Candidate;
import trex.journal.Json;
import trex.sequencer.ingest.CandidateInput;
import trex.sequencer.ingest.DecisionInput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Request body binding (SPEC §3.3 step 1): parse to a tree, then bind each element individually.
 * Structural problems throw {@link BadRequestException} (→ 400); element problems become rejects.
 */
final class Binding {

    static final class BadRequestException extends Exception {
        BadRequestException(String message) {
            super(message);
        }
    }

    record Request<T>(boolean allOrNone, List<T> items) {}

    private static final Set<String> DECISION_FIELDS = Set.of("decisionRef", "action", "externalId", "legA", "legB", "comment");

    private Binding() {}

    static Request<CandidateInput> candidates(byte[] body) throws BadRequestException {
        JsonNode root = parse(body);
        boolean allOrNone = allOrNone(root, Set.of("allOrNone", "batch"));
        List<CandidateInput> items = new ArrayList<>();
        for (JsonNode el : array(root, "batch")) {
            items.add(candidate(el));
        }
        return new Request<>(allOrNone, items);
    }

    static Request<DecisionInput> decisions(byte[] body) throws BadRequestException {
        JsonNode root = parse(body);
        boolean allOrNone = allOrNone(root, Set.of("allOrNone", "decisions"));
        List<DecisionInput> items = new ArrayList<>();
        for (JsonNode el : array(root, "decisions")) {
            items.add(decision(el));
        }
        return new Request<>(allOrNone, items);
    }

    private static CandidateInput candidate(JsonNode el) {
        if (!el.isObject()) {
            return CandidateInput.unbindable(null, "batch element is not an object");
        }
        String ref = el.path("candidateRef").isTextual() ? el.get("candidateRef").asText() : null;
        for (String field : List.of("amount", "balance")) {
            JsonNode v = el.get(field);
            if (v == null || !v.isIntegralNumber() || !v.canConvertToLong()) {
                return CandidateInput.unbindable(ref, field + " must be present as integer cents");
            }
        }
        try {
            Candidate c = Json.mapper().treeToValue(el, Candidate.class);
            return new CandidateInput(ref, c, null);
        } catch (IOException | IllegalArgumentException e) {
            return CandidateInput.unbindable(ref, "invalid candidate: " + firstLine(e.getMessage()));
        }
    }

    private static DecisionInput decision(JsonNode el) {
        if (!el.isObject()) {
            return DecisionInput.unbindable(null, "decision is not an object");
        }
        String ref = el.path("decisionRef").isTextual() ? el.get("decisionRef").asText() : null;
        var names = el.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!DECISION_FIELDS.contains(name)) {
                return DecisionInput.unbindable(ref, "unknown field: " + name);
            }
            if (!el.get(name).isTextual() && !el.get(name).isNull()) {
                return DecisionInput.unbindable(ref, name + " must be a string");
            }
        }
        String actionName = text(el, "action");
        DecisionInput.Action action;
        try {
            action = DecisionInput.Action.valueOf(actionName == null ? "" : actionName);
        } catch (IllegalArgumentException _) {
            return DecisionInput.unbindable(ref, "unknown action: " + actionName);
        }
        return new DecisionInput(ref, action, text(el, "externalId"), text(el, "legA"), text(el, "legB"),
            text(el, "comment"), null);
    }

    private static JsonNode parse(byte[] body) throws BadRequestException {
        try {
            JsonNode root = Json.mapper().readTree(body);
            if (root == null || !root.isObject()) {
                throw new BadRequestException("request body must be a JSON object");
            }
            return root;
        } catch (IOException e) {
            throw new BadRequestException("malformed JSON: " + firstLine(e.getMessage()));
        }
    }

    private static boolean allOrNone(JsonNode root, Set<String> allowed) throws BadRequestException {
        var names = root.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new BadRequestException("unknown top-level field: " + name);
            }
        }
        JsonNode v = root.get("allOrNone");
        if (v == null || v.isNull()) {
            return false;
        }
        if (!v.isBoolean()) {
            throw new BadRequestException("allOrNone must be a boolean");
        }
        return v.booleanValue();
    }

    private static JsonNode array(JsonNode root, String field) throws BadRequestException {
        JsonNode v = root.get(field);
        if (v == null || !v.isArray()) {
            throw new BadRequestException("'" + field + "' must be an array");
        }
        return v;
    }

    private static String text(JsonNode el, String field) {
        JsonNode v = el.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
