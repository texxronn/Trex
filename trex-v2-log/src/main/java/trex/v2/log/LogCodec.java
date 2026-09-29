package trex.v2.log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import trex.v2.core.Action;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.LogLine;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The wire format of a v2 log line (V2-PROPOSAL.md §6.1, §6.2, §6.7). Written by hand rather than
 * by Jackson's polymorphism so the field order is exactly the proposal's and the discriminator
 * ({@code kind}, {@code action}) sits where a human reading the log expects it.
 *
 * <p>Facts write nulls ({@code "receipt":null}, {@code "evidenceId":null}); decisions omit
 * {@code user} for {@code system}/{@code migrated} and include {@code comment} (null when absent).
 */
public final class LogCodec {

    private static final String KIND_FACT = "fact";
    private static final String KIND_DECISION = "decision";

    private LogCodec() {}

    public static byte[] encode(LogLine line) {
        try {
            return Json.mapper().writeValueAsBytes(toNode(line));
        } catch (IOException e) {
            throw new IllegalStateException("cannot encode log line", e);
        }
    }

    public static String encodeString(LogLine line) {
        return new String(encode(line), java.nio.charset.StandardCharsets.UTF_8);
    }

    public static ObjectNode toNode(LogLine line) {
        ObjectNode o = Json.mapper().createObjectNode();
        o.put("n", line.n());
        o.put("kind", line.kind());
        if (line instanceof Fact f) {
            fact(f, o);
        } else if (line instanceof Decision d) {
            decision(d, o);
        } else {
            throw new IllegalArgumentException("unsupported log line " + line.getClass());
        }
        return o;
    }

    private static void fact(Fact f, ObjectNode o) {
        o.put("v", f.v());
        o.put("externalId", f.externalId());
        o.put("accountRef", f.accountRef());
        o.put("date", f.date().toString());
        o.put("amount", f.amount());
        o.put("balance", f.balance());
        o.put("rawDescription", f.rawDescription());
        putNullable(o, "receipt", f.receipt());
        o.put("occ", f.occ());
        o.put("observation", f.observation().wire());
        o.put("sourceType", f.sourceType());
        o.put("provenance", f.provenance().wire());
        putNullable(o, "evidenceId", f.evidenceId());
        putNullable(o, "parser", f.parser());
        o.put("ingestedAt", f.ingestedAt().toString());
    }

    private static void decision(Decision d, ObjectNode o) {
        o.put("action", d.action().wire());
        switch (d) {
            case Decision.Pair p -> {
                o.put("legA", p.legA());
                o.put("legB", p.legB());
                putNullable(o, "comment", p.comment());
            }
            case Decision.Unpair u -> {
                o.put("legA", u.legA());
                o.put("legB", u.legB());
                putNullable(o, "comment", u.comment());
            }
            case Decision.MarkExternal m -> {
                o.put("externalId", m.externalId());
                putNullable(o, "comment", m.comment());
            }
            case Decision.Settle s -> {
                o.put("pendingId", s.pendingId());
                o.put("postedId", s.postedId());
                putNullable(o, "comment", s.comment());
            }
            case Decision.Dismiss dis -> {
                o.put("item", dis.item());
                array(o, "externalIds", dis.externalIds());
                putNullable(o, "comment", dis.comment());
            }
            case Decision.Pin pin -> {
                array(o, "externalIds", pin.externalIds());
                o.put("category", pin.category());
                putNullable(o, "comment", pin.comment());
            }
            case Decision.Unpin up -> {
                array(o, "externalIds", up.externalIds());
                putNullable(o, "comment", up.comment());
            }
            case Decision.Supersede sup -> {
                o.put("fromId", sup.fromId());
                o.put("toId", sup.toId());
                o.put("reason", sup.reason());
            }
            case Decision.Retire r -> {
                o.put("externalId", r.externalId());
                o.put("reason", r.reason());
            }
            case Decision.Revoke rv -> {
                o.put("target", rv.target());
                putNullable(o, "comment", rv.comment());
            }
            case Decision.UserAck ack -> {
                o.put("period", ack.period());
                o.put("throughN", ack.throughN());
                o.put("configRevision", ack.configRevision());
                o.put("deriveVersion", ack.deriveVersion());
                o.put("hashVersion", ack.hashVersion());
                o.put("stateHash", ack.stateHash());
                putNullable(o, "comment", ack.comment());
            }
            case Decision.Note note -> {
                putNullable(o, "externalId", note.externalId());
                o.put("text", note.text());
            }
        }
        o.put("actor", d.actor().wire());
        if (d.user() != null) {
            o.put("user", d.user());
        }
        o.put("at", d.at().toString());
    }

    // ---- decode -----------------------------------------------------------------------------

    public static LogLine parse(byte[] line) {
        try {
            return decode(Json.mapper().readTree(line));
        } catch (IOException e) {
            throw new JournalCorruptException("unparseable log line", e);
        }
    }

    public static LogLine decode(JsonNode node) {
        String kind = text(node, "kind");
        return switch (kind) {
            case KIND_FACT -> decodeFact(node);
            case KIND_DECISION -> decodeDecision(node);
            default -> throw new JournalCorruptException("unknown log line kind '" + kind + "'", null);
        };
    }

    private static Fact decodeFact(JsonNode n) {
        return new Fact(
            lng(n, "n"),
            intg(n, "v"),
            text(n, "externalId"),
            text(n, "accountRef"),
            LocalDate.parse(text(n, "date")),
            lng(n, "amount"),
            lng(n, "balance"),
            text(n, "rawDescription"),
            opt(n, "receipt"),
            intg(n, "occ"),
            Observation.fromWire(text(n, "observation")),
            text(n, "sourceType"),
            Provenance.fromWire(text(n, "provenance")),
            opt(n, "evidenceId"),
            opt(n, "parser"),
            Instant.parse(text(n, "ingestedAt")));
    }

    private static Decision decodeDecision(JsonNode n) {
        long seq = lng(n, "n");
        Action action = Action.fromWire(text(n, "action"));
        Actor actor = Actor.fromWire(text(n, "actor"));
        String user = opt(n, "user");
        Instant at = Instant.parse(text(n, "at"));
        return switch (action) {
            case PAIR -> new Decision.Pair(seq, text(n, "legA"), text(n, "legB"), opt(n, "comment"), actor, user, at);
            case UNPAIR -> new Decision.Unpair(seq, text(n, "legA"), text(n, "legB"), opt(n, "comment"), actor, user, at);
            case MARK_EXTERNAL -> new Decision.MarkExternal(seq, text(n, "externalId"), opt(n, "comment"), actor, user, at);
            case SETTLE -> new Decision.Settle(seq, text(n, "pendingId"), text(n, "postedId"), opt(n, "comment"), actor, user, at);
            case DISMISS -> new Decision.Dismiss(seq, text(n, "item"), list(n, "externalIds"), opt(n, "comment"), actor, user, at);
            case PIN -> new Decision.Pin(seq, list(n, "externalIds"), text(n, "category"), opt(n, "comment"), actor, user, at);
            case UNPIN -> new Decision.Unpin(seq, list(n, "externalIds"), opt(n, "comment"), actor, user, at);
            case SUPERSEDE -> new Decision.Supersede(seq, text(n, "fromId"), text(n, "toId"), text(n, "reason"), actor, user, at);
            case RETIRE -> new Decision.Retire(seq, text(n, "externalId"), text(n, "reason"), actor, user, at);
            case REVOKE -> new Decision.Revoke(seq, lng(n, "target"), opt(n, "comment"), actor, user, at);
            case USER_ACK -> new Decision.UserAck(seq, text(n, "period"), lng(n, "throughN"),
                text(n, "configRevision"), text(n, "deriveVersion"), text(n, "hashVersion"),
                text(n, "stateHash"), opt(n, "comment"), actor, user, at);
            case NOTE -> new Decision.Note(seq, opt(n, "externalId"), text(n, "text"), actor, user, at);
        };
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static void putNullable(ObjectNode o, String field, String value) {
        if (value == null) {
            o.putNull(field);
        } else {
            o.put(field, value);
        }
    }

    private static void array(ObjectNode o, String field, List<String> values) {
        ArrayNode a = o.putArray(field);
        values.forEach(a::add);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            throw new JournalCorruptException("missing field '" + field + "'", null);
        }
        return v.asText();
    }

    private static String opt(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static long lng(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.canConvertToLong()) {
            throw new JournalCorruptException("missing integer field '" + field + "'", null);
        }
        return v.asLong();
    }

    private static int intg(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.canConvertToInt()) {
            throw new JournalCorruptException("missing integer field '" + field + "'", null);
        }
        return v.asInt();
    }

    private static List<String> list(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isArray()) {
            throw new JournalCorruptException("missing array field '" + field + "'", null);
        }
        List<String> out = new ArrayList<>();
        v.forEach(e -> out.add(e.asText()));
        return out;
    }
}
