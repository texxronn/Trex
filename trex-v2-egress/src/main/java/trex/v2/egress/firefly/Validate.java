package trex.v2.egress.firefly;

import com.fasterxml.jackson.databind.JsonNode;
import trex.v2.egress.firefly.FireflyClient.Existing;
import trex.v2.egress.hub.HubClient.HubUnit;
import trex.v2.egress.hub.HubClient.ProjectionState;
import trex.v2.egress.hub.HubClient.Units;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The read-only Firefly contract check (V2-SPEC.md §11.1, D9/R2): what Firefly holds, against the
 * hub's current units and the recorded state. It classifies and never writes — not Firefly, not the
 * hub — so the "was known" evidence survives repeated runs and a timer can run it.
 *
 * <p><b>A violation is something Firefly changed, never something trex moved on from</b> (review
 * V1/V2). A rule edit leaves stale {@code rules=} and moves {@code n=}, and a restatement is work
 * the next apply does; neither may exit 1 on an ordinary day. So the first notes line is checked for
 * <em>shape</em>, never its values, and content is judged against the fingerprint trex last
 * <em>recorded</em>: Firefly differing from it is {@code DRIFT}; Firefly matching it while trex's
 * expected content differs is {@code BEHIND} (informational).
 */
public final class Validate {

    /** One Firefly-side edit (or normal state): what it is, on which unit and group, and why. */
    public record Finding(String kind, String unitId, String groupId, String detail) {}

    /** The first notes line, in the shape {@link Projection#of} writes — values are never compared. */
    private static final Pattern NOTES = Pattern.compile("trex n=\\d+(?: .*)?");

    private Validate() {}

    /**
     * Every finding, sorted. Pure and read-only: the hub's units and recorded state and Firefly's
     * inventory in, a list out. The caller decides which kinds exit non-zero. Before anything else
     * it refuses an unmapped ref the same way the preflight does — {@code BEHIND}/{@code DRIFT} call
     * {@link Projection#of}, which throws on one, and a checker must name the ref, never a stack
     * trace.
     */
    public static List<Finding> check(Units units, List<ProjectionState> state,
                                      List<Existing> inventory, AccountMap accounts) {
        refuseUnmapped(units, accounts);

        Map<String, HubUnit> byId = new TreeMap<>();
        units.units().forEach(u -> byId.put(u.unitId(), u));
        Map<String, Existing> byGroup = new TreeMap<>();
        inventory.forEach(e -> byGroup.put(e.groupId(), e));

        String revision = units.configRevision();
        List<Finding> out = new ArrayList<>();

        for (ProjectionState row : state) {
            HubUnit unit = byId.get(row.unitId());
            if (unit == null) {
                out.add(new Finding("ORPHAN", row.unitId(), row.groupId(),
                    "no current unit; --remove-orphans is the instruction"));
                continue;
            }
            Existing existing = byGroup.get(row.groupId());
            if (existing == null) {
                out.add(new Finding("MISSING", row.unitId(), row.groupId(),
                    "the group is gone; run --verify then --apply to recreate it"));
                continue;
            }
            JsonNode splits = existing.group().path("attributes").path("transactions");
            if (splits.isEmpty() || !FireflyClient.isOurs(splits.get(0))) {
                // Ownership is gone: named by the inventory pass as UNTAGGED. Nothing else here owns it.
                continue;
            }
            JsonNode first = splits.get(0);

            if (!row.unitId().equals(existing.externalId())) {
                out.add(new Finding("TAMPERED", row.unitId(), row.groupId(),
                    "external_id is \"" + existing.externalId() + "\", not the unit id"));
            }
            if (!NOTES.matcher(firstLine(first.path("notes").asText(""))).matches()) {
                out.add(new Finding("TAMPERED", row.unitId(), row.groupId(),
                    "the first notes line is not ours: \"" + firstLine(first.path("notes").asText("")) + "\""));
            }
            if ("TRANSFER".equals(unit.unitKind())) {
                List<String> notesLegs = FireflyClient.legs(first.path("notes").asText(""));
                if (!notesLegs.isEmpty()) {
                    // Resolve the notes' legs the same way the hub resolves a unit's: a leg superseded
                    // by a re-parse resolves to its current id (default: itself), so a re-keyed group
                    // is not TAMPERED. A group with no legs= (pre-Stage-5, or EXTERNAL) is never judged.
                    Map<String, String> resolved = units.resolved() == null ? Map.of() : units.resolved();
                    List<String> legs = new ArrayList<>();
                    for (String leg : notesLegs) {
                        legs.add(resolved.getOrDefault(leg, leg));
                    }
                    List<String> unitLegs = unit.legs() == null ? List.of() : unit.legs();
                    if (!new HashSet<>(legs).equals(new HashSet<>(unitLegs))) {
                        out.add(new Finding("TAMPERED", row.unitId(), row.groupId(),
                            "the notes legs " + legs + " are not the unit's legs " + unitLegs));
                    }
                }
            }

            boolean single = splits.size() == 1;
            boolean verified = Content.verified(row.stateHash()) && !Content.HAND_SPLIT.equals(row.stateHash());
            if (single && verified) {
                String observed = Content.observed(first, accounts.ids()).fingerprint();
                if (!observed.equals(row.stateHash())) {
                    out.add(new Finding("DRIFT", row.unitId(), row.groupId(),
                        "content differs from what trex wrote; the next apply overwrites it (D2)"));
                } else if (!expected(unit, revision, accounts).fingerprint().equals(row.stateHash())) {
                    out.add(new Finding("BEHIND", row.unitId(), row.groupId(),
                        "trex has moved; the next apply updates it"));
                }
            }
            if (!single) {
                long sum = 0;
                for (JsonNode s : splits) {
                    sum += Content.cents(s.path("amount").asText("0"));
                }
                long wanted = Content.cents(expected(unit, revision, accounts).amount());
                if (sum != wanted) {
                    out.add(new Finding("HAND_SPLIT", row.unitId(), row.groupId(),
                        "your splits total " + Projection.amount(sum) + "; the bank says "
                            + Projection.amount(wanted) + " and they are left as you made them"));
                }
            }
            String tagCategory = FireflyClient.tagCategory(first);
            String current = first.path("category_name").asText(null);
            if (tagCategory != null && !tagCategory.equals(current)) {
                out.add(new Finding("CATEGORY", row.unitId(), row.groupId(),
                    "category \"" + (current == null ? "" : current) + "\" is yours; our tag says \""
                        + tagCategory + "\""));
            }
        }

        for (Existing e : inventory) {
            if (e.externalId() == null) {
                continue;                  // no external_id to name it by; ownership is irrelevant
            }
            HubUnit unit = byId.get(e.externalId());
            if (unit == null) {
                continue;                  // not a current unit; ownership is irrelevant
            }
            JsonNode splits = e.group().path("attributes").path("transactions");
            if (!splits.isEmpty() && !FireflyClient.isOurs(splits.get(0))) {
                out.add(new Finding("UNTAGGED", unit.unitId(), e.groupId(),
                    "the trex tag is gone; ownership lost — restore it or recreate via --verify then --apply"));
            }
        }

        out.sort(Comparator.comparing(Finding::kind).thenComparing(Finding::unitId)
            .thenComparing(Finding::groupId).thenComparing(Finding::detail));
        return out;
    }

    private static Content expected(HubUnit unit, String revision, AccountMap accounts) {
        return Content.expected(Projection.of(unit, revision, accounts).split());
    }

    private static String firstLine(String notes) {
        if (notes == null) {
            return "";
        }
        int nl = notes.indexOf('\n');
        return nl < 0 ? notes : notes.substring(0, nl);
    }

    /** The same refusal {@link FireflyEgress}'s preflight makes: name the refs, write nothing. */
    private static void refuseUnmapped(Units units, AccountMap accounts) {
        List<String> refs = new ArrayList<>();
        units.units().forEach(u -> {
            refs.add(u.accountRef());
            refs.add(u.toAccountRef());
        });
        List<String> missing = accounts.missing(refs);
        if (!missing.isEmpty()) {
            throw new FireflyEgress.Refused("firefly.yaml maps no Firefly account for: "
                + String.join(", ", missing) + ". Add them (name and type) and rerun; nothing has been written.");
        }
    }
}
