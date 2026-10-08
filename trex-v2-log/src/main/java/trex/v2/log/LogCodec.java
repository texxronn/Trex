package trex.v2.log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import trex.v2.core.Action;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
import trex.v2.core.Envelope;
import trex.v2.core.Fact;
import trex.v2.core.IngestEvent;
import trex.v2.core.LogLine;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.core.Unknown;
import trex.v2.core.derive.AmountKind;
import trex.v2.core.derive.Cadence;
import trex.v2.core.derive.CommitmentKind;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The wire format of a v2 log line (V2-PROPOSAL.md §6.1, §6.2, §6.7). Written by hand rather than
 * by Jackson's polymorphism so the field order is exactly the proposal's and the discriminator
 * sits where a human reading the log expects it.
 *
 * <p>The header (§6) leads every line: {@code n, kind, v, atMs, env, source, target}, then {@code
 * at} (a readable echo of {@code atMs}) and the kind-specific body. {@code kind} is namespaced
 * ({@code trex.fact} / {@code trex.decision}); a reader skips an unknown kind (§6).
 */
public final class LogCodec {

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
        Envelope e = line.envelope();
        o.put("n", e.n());
        o.put("kind", e.kind());
        o.put("v", e.v());
        o.put("atMs", e.atMs());
        o.put("env", e.env());
        o.put("source", e.source());
        o.put("target", e.target());
        o.put("at", e.instant().toString());   // readable echo; atMs is canonical
        if (line instanceof Fact f) {
            fact(f, o);
        } else if (line instanceof Decision d) {
            decision(d, o);
        } else if (line instanceof IngestEvent ev) {
            ingest(ev, o);
        } else if (line instanceof Unknown) {
            // envelope only; an unknown kind carries no body this build can write
        } else {
            throw new IllegalArgumentException("unsupported log line " + line.getClass());
        }
        return o;
    }

    private static void fact(Fact f, ObjectNode o) {
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
            case Decision.AttachAccount aa -> {
                array(o, "externalIds", aa.externalIds());
                o.put("account", aa.account());
                putNullable(o, "comment", aa.comment());
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
            case Decision.MarkNoop m -> {
                o.put("externalId", m.externalId());
                o.put("reason", m.reason());
            }
            case Decision.UnmarkNoop u -> {
                o.put("externalId", u.externalId());
                putNullable(o, "comment", u.comment());
            }
            case Decision.Revoke rv -> {
                o.put("revokes", rv.target());   // not "target": that is the envelope's field
                putNullable(o, "comment", rv.comment());
            }
            case Decision.UserAck ack -> {
                o.put("externalId", ack.externalId());
                o.put("configRevision", ack.configRevision());
                o.put("deriveVersion", ack.deriveVersion());
                o.put("hashVersion", ack.hashVersion());
                o.put("stateHash", ack.stateHash());
                putNullable(o, "comment", ack.comment());
            }
            case Decision.UserUnack unack -> {
                o.put("externalId", unack.externalId());
                putNullable(o, "comment", unack.comment());
            }
            case Decision.Note note -> {
                putNullable(o, "externalId", note.externalId());
                o.put("text", note.text());
            }
            case Decision.DeclareCommitment dc -> {
                o.put("commitmentId", dc.commitmentId());
                o.put("name", dc.name());
                o.put("direction", dc.direction());
                o.put("cadence", dc.cadence().wire());
                o.put("amountKind", dc.amountKind().wire());
                o.put("commitmentKind", dc.commitmentKind().wire());
                matches(o, "matches", dc.matches());
                putNullableLong(o, "amount", dc.amount());
                putNullable(o, "anchor", dc.anchor() == null ? null : dc.anchor().toString());
                putNullable(o, "fromCandidate", dc.fromCandidate());
                putNullable(o, "comment", dc.comment());
            }
            case Decision.RetireCommitment rc -> {
                o.put("commitmentId", rc.commitmentId());
                o.put("endedAt", rc.endedAt().toString());
                o.put("reason", rc.reason());
            }
            case Decision.IgnoreRecurring ir -> {
                o.put("candidate", ir.candidate());
                o.put("reason", ir.reason());
            }
            case Decision.PinCommitment pc -> {
                o.put("commitmentId", pc.commitmentId());
                array(o, "externalIds", pc.externalIds());
                putNullable(o, "comment", pc.comment());
            }
            case Decision.UnpinCommitment uc -> {
                array(o, "externalIds", uc.externalIds());
                putNullable(o, "comment", uc.comment());
            }
            case Decision.NoteCommitment nc -> {
                o.put("commitmentId", nc.commitmentId());
                o.put("text", nc.text());
            }
            case Decision.SettleOccurrence so -> {
                o.put("commitmentId", so.commitmentId());
                dates(o, "dueDates", so.dueDates());
                putNullable(o, "comment", so.comment());
            }
            case Decision.ExcludeCommitment ec -> {
                o.put("commitmentId", ec.commitmentId());
                array(o, "externalIds", ec.externalIds());
                putNullable(o, "comment", ec.comment());
            }
            case Decision.IncludeCommitment ic -> {
                o.put("commitmentId", ic.commitmentId());
                array(o, "externalIds", ic.externalIds());
                putNullable(o, "comment", ic.comment());
            }
        }
        o.put("actor", d.actor().wire());
        if (d.user() != null) {
            o.put("user", d.user());
        }
    }

    private static void ingest(IngestEvent e, ObjectNode o) {
        o.put("phase", e.phase());
        o.put("batch", e.batch());
        putNullable(o, "evidence", e.evidence());
        putNullable(o, "file", e.file());
        putNullable(o, "account", e.accountRef());
        putNullable(o, "sourceType", e.sourceType());
        putNullable(o, "parser", e.parser());
        putNullableInt(o, "appended", e.appended());
        putNullableInt(o, "duplicate", e.duplicate());
        putNullableInt(o, "flagged", e.flagged());
        putNullable(o, "status", e.status());
    }

    // ---- decode -----------------------------------------------------------------------------

    /** The namespaced kinds this codec understands. */
    public static boolean isKnownKind(String kind) {
        return Fact.KIND.equals(kind) || Decision.KIND.equals(kind) || IngestEvent.KIND.equals(kind);
    }

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
            case Fact.KIND -> decodeFact(node);
            case Decision.KIND -> decodeDecision(node);
            case IngestEvent.KIND -> decodeIngest(node);
            // A well-formed envelope with a kind this build does not know: forward-compatible.
            default -> new Unknown(envelope(node));
        };
    }

    private static Envelope envelope(JsonNode n) {
        return new Envelope(lng(n, "n"), text(n, "kind"), intg(n, "v"), lng(n, "atMs"),
            text(n, "env"), text(n, "source"), text(n, "target"));
    }

    private static Fact decodeFact(JsonNode n) {
        return new Fact(
            envelope(n),
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
            opt(n, "parser"));
    }

    private static Decision decodeDecision(JsonNode n) {
        Envelope e = envelope(n);
        Action action = Action.fromWire(text(n, "action"));
        Actor actor = Actor.fromWire(text(n, "actor"));
        String user = opt(n, "user");
        return switch (action) {
            case PAIR -> new Decision.Pair(e, text(n, "legA"), text(n, "legB"), opt(n, "comment"), actor, user);
            case UNPAIR -> new Decision.Unpair(e, text(n, "legA"), text(n, "legB"), opt(n, "comment"), actor, user);
            case MARK_EXTERNAL -> new Decision.MarkExternal(e, text(n, "externalId"), opt(n, "comment"), actor, user);
            case SETTLE -> new Decision.Settle(e, text(n, "pendingId"), text(n, "postedId"), opt(n, "comment"), actor, user);
            case DISMISS -> new Decision.Dismiss(e, text(n, "item"), list(n, "externalIds"), opt(n, "comment"), actor, user);
            case PIN -> new Decision.Pin(e, list(n, "externalIds"), text(n, "category"), opt(n, "comment"), actor, user);
            case UNPIN -> new Decision.Unpin(e, list(n, "externalIds"), opt(n, "comment"), actor, user);
            case ATTACH_ACCOUNT -> new Decision.AttachAccount(e, list(n, "externalIds"),
                text(n, "account"), opt(n, "comment"), actor, user);
            case SUPERSEDE -> new Decision.Supersede(e, text(n, "fromId"), text(n, "toId"), text(n, "reason"), actor, user);
            case RETIRE -> new Decision.Retire(e, text(n, "externalId"), text(n, "reason"), actor, user);
            case MARK_NOOP -> new Decision.MarkNoop(e, text(n, "externalId"), text(n, "reason"), actor, user);
            case UNMARK_NOOP -> new Decision.UnmarkNoop(e, text(n, "externalId"), opt(n, "comment"), actor, user);
            case REVOKE -> new Decision.Revoke(e, lng(n, "revokes"), opt(n, "comment"), actor, user);
            case USER_ACK -> new Decision.UserAck(e, text(n, "externalId"),
                text(n, "configRevision"), text(n, "deriveVersion"), text(n, "hashVersion"),
                text(n, "stateHash"), opt(n, "comment"), actor, user);
            case USER_UNACK -> new Decision.UserUnack(e, text(n, "externalId"), opt(n, "comment"), actor, user);
            case NOTE -> new Decision.Note(e, opt(n, "externalId"), text(n, "text"), actor, user);
            case DECLARE_COMMITMENT -> new Decision.DeclareCommitment(e, text(n, "commitmentId"),
                text(n, "name"), text(n, "direction"), Cadence.fromWire(text(n, "cadence")),
                AmountKind.fromWire(text(n, "amountKind")),
                CommitmentKind.fromWire(text(n, "commitmentKind")),
                matchList(n, "matches"), optLong(n, "amount"), optDate(n, "anchor"),
                opt(n, "fromCandidate"), opt(n, "comment"), actor, user);
            case RETIRE_COMMITMENT -> new Decision.RetireCommitment(e, text(n, "commitmentId"),
                LocalDate.parse(text(n, "endedAt")), text(n, "reason"), actor, user);
            case IGNORE_RECURRING -> new Decision.IgnoreRecurring(e, text(n, "candidate"),
                text(n, "reason"), actor, user);
            case PIN_COMMITMENT -> new Decision.PinCommitment(e, text(n, "commitmentId"),
                list(n, "externalIds"), opt(n, "comment"), actor, user);
            case UNPIN_COMMITMENT -> new Decision.UnpinCommitment(e, list(n, "externalIds"),
                opt(n, "comment"), actor, user);
            case NOTE_COMMITMENT -> new Decision.NoteCommitment(e, text(n, "commitmentId"),
                text(n, "text"), actor, user);
            case SETTLE_OCCURRENCE -> new Decision.SettleOccurrence(e, text(n, "commitmentId"),
                dateList(n, "dueDates"), opt(n, "comment"), actor, user);
            case EXCLUDE_COMMITMENT -> new Decision.ExcludeCommitment(e, text(n, "commitmentId"),
                list(n, "externalIds"), opt(n, "comment"), actor, user);
            case INCLUDE_COMMITMENT -> new Decision.IncludeCommitment(e, text(n, "commitmentId"),
                list(n, "externalIds"), opt(n, "comment"), actor, user);
        };
    }

    private static IngestEvent decodeIngest(JsonNode n) {
        return new IngestEvent(
            envelope(n),
            text(n, "phase"),
            text(n, "batch"),
            opt(n, "evidence"),
            opt(n, "file"),
            opt(n, "account"),
            opt(n, "sourceType"),
            opt(n, "parser"),
            optInt(n, "appended"),
            optInt(n, "duplicate"),
            optInt(n, "flagged"),
            opt(n, "status"));
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

    private static void matches(ObjectNode o, String field, List<Decision.Match> values) {
        ArrayNode a = o.putArray(field);
        for (Decision.Match m : values) {
            ObjectNode mo = a.addObject();
            mo.put("match", m.match());
            putNullable(mo, "account", m.account());
        }
    }

    private static void dates(ObjectNode o, String field, List<LocalDate> values) {
        ArrayNode a = o.putArray(field);
        values.forEach(d -> a.add(d.toString()));
    }

    private static void putNullableLong(ObjectNode o, String field, Long value) {
        if (value == null) {
            o.putNull(field);
        } else {
            o.put(field, value.longValue());
        }
    }

    private static void putNullableInt(ObjectNode o, String field, Integer value) {
        if (value == null) {
            o.putNull(field);
        } else {
            o.put(field, value.intValue());
        }
    }

    private static Integer optInt(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asInt();
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

    private static List<Decision.Match> matchList(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isArray()) {
            throw new JournalCorruptException("missing array field '" + field + "'", null);
        }
        List<Decision.Match> out = new ArrayList<>();
        for (JsonNode e : v) {
            out.add(new Decision.Match(text(e, "match"), opt(e, "account")));
        }
        return out;
    }

    private static List<LocalDate> dateList(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isArray()) {
            throw new JournalCorruptException("missing array field '" + field + "'", null);
        }
        List<LocalDate> out = new ArrayList<>();
        v.forEach(e -> out.add(LocalDate.parse(e.asText())));
        return out;
    }

    private static Long optLong(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asLong();
    }

    private static LocalDate optDate(JsonNode n, String field) {
        String v = opt(n, field);
        return v == null ? null : LocalDate.parse(v);
    }
}
