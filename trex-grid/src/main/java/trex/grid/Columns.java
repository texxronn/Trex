package trex.grid;

import trex.core.CanonicalEvent;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Sortable grid columns. Text compares case-insensitively; nulls always sort last. */
final class Columns {

    private Columns() {}

    private static final Map<String, Function<CanonicalEvent, ? extends Comparable<?>>> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("n", CanonicalEvent::n);
        KEYS.put("date", CanonicalEvent::date);
        KEYS.put("accountRef", e -> lower(e.accountRef()));
        KEYS.put("toAccountRef", e -> lower(e.toAccountRef()));
        KEYS.put("amount", CanonicalEvent::amount);
        KEYS.put("currency", CanonicalEvent::currency);
        KEYS.put("description", e -> lower(e.description()));
        KEYS.put("typeHint", e -> e.typeHint().name());
        KEYS.put("state", e -> e.state().name());
        KEYS.put("flags", e -> e.flags().isEmpty() ? null : e.flags().toString());
        KEYS.put("confidence", e -> e.confidence() == null ? null : e.confidence().name());
        KEYS.put("provenance", e -> e.provenance() == null ? null : e.provenance().name());
        KEYS.put("source", e -> lower(e.source()));
        KEYS.put("receipt", e -> lower(e.receipt()));
        KEYS.put("transferKey", e -> lower(e.transferKey()));
        KEYS.put("comment", e -> lower(e.comment()));
        KEYS.put("ingestedAt", CanonicalEvent::ingestedAt);
        KEYS.put("externalId", e -> lower(e.externalId()));
    }

    static boolean exists(String column) {
        return KEYS.containsKey(column);
    }

    /** Comparator for one sort key: direction applies to values, nulls stay last either way. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Comparator<CanonicalEvent> comparator(GridQuery.SortKey key) {
        Function<CanonicalEvent, Comparable> extract = (Function) KEYS.get(key.column());
        Comparator<Comparable> values = key.descending() ? Comparator.<Comparable>reverseOrder() : Comparator.<Comparable>naturalOrder();
        return Comparator.comparing(extract, Comparator.nullsLast(values));
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase();
    }
}
