package trex.grid;

import trex.core.CanonicalEvent;
import trex.core.TypeHint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Answers grid queries against a pinned snapshot: filter, sort (ties by n ascending), page.
 * Each distinct (generation, view, asOfN, filters, sort) result is computed once and cached. SPEC §5.5.
 */
final class GridIndex {

    record Total(String currency, long amount, long count) {}

    record Page(long asOfN, String view, int page, int size, int total, List<CanonicalEvent> rows, List<Total> totals) {}

    private record Key(long generation, GridQuery.View view, long asOfN, GridQuery.Filters filters, List<GridQuery.SortKey> sort) {}

    private record Result(List<CanonicalEvent> rows, List<Total> totals) {}

    private static final int CACHE_ENTRIES = 16;

    private final Map<Key, Result> cache = Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Result> eldest) {
            return size() > CACHE_ENTRIES;
        }
    });

    Page query(GridData data, GridQuery q) {
        long head = data.n();
        long asOfN = q.asOfN() == null || q.asOfN() > head ? head : q.asOfN();
        Key key = new Key(data.generation(), q.view(), asOfN, q.filters(), q.sort());
        Result result = cache.computeIfAbsent(key, k -> compute(data, k));
        int from = Math.min((q.page() - 1) * q.size(), result.rows().size());
        int to = Math.min(from + q.size(), result.rows().size());
        return new Page(asOfN, q.view().name().toLowerCase(), q.page(), q.size(), result.rows().size(),
            result.rows().subList(from, to), result.totals());
    }

    private static Result compute(GridData data, Key key) {
        List<CanonicalEvent> pinned = prefix(data.lines(), key.asOfN());
        List<CanonicalEvent> base;
        if (key.view() == GridQuery.View.TRANSACTIONS) {
            Map<String, CanonicalEvent> latest = new LinkedHashMap<>();
            pinned.forEach(l -> latest.put(l.externalId(), l));
            base = new ArrayList<>(latest.values());
        } else {
            base = new ArrayList<>(pinned);
        }
        List<CanonicalEvent> rows = new ArrayList<>(base.size());
        for (CanonicalEvent e : base) {
            if (matches(e, key.filters())) {
                rows.add(e);
            }
        }
        Comparator<CanonicalEvent> order = null;
        for (GridQuery.SortKey s : key.sort()) {
            order = order == null ? Columns.comparator(s) : order.thenComparing(Columns.comparator(s));
        }
        Comparator<CanonicalEvent> byN = Comparator.comparingLong(CanonicalEvent::n);
        rows.sort(order == null ? byN : order.thenComparing(byN));

        List<Total> totals = List.of();
        if (key.view() == GridQuery.View.TRANSACTIONS) {
            Map<String, long[]> sums = new TreeMap<>();
            for (CanonicalEvent e : rows) {
                if (e.typeHint() == TypeHint.TRANSFER) {
                    continue;
                }
                long[] acc = sums.computeIfAbsent(String.valueOf(e.currency()), c -> new long[2]);
                acc[0] = Math.addExact(acc[0], e.amount());
                acc[1]++;
            }
            totals = sums.entrySet().stream().map(en -> new Total(en.getKey(), en.getValue()[0], en.getValue()[1])).toList();
        }
        return new Result(List.copyOf(rows), totals);
    }

    /** Lines with n <= asOfN; lines are in strictly increasing n order. */
    private static List<CanonicalEvent> prefix(List<CanonicalEvent> lines, long asOfN) {
        int lo = 0;
        int hi = lines.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).n() <= asOfN) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lines.subList(0, lo);
    }

    private static boolean matches(CanonicalEvent e, GridQuery.Filters f) {
        if (f.account() != null && !f.account().equals(e.accountRef()) && !f.account().equals(e.toAccountRef())) {
            return false;
        }
        if (f.state() != null && f.state() != e.state()) {
            return false;
        }
        if (f.type() != null && f.type() != e.typeHint()) {
            return false;
        }
        if (f.from() != null && (e.date() == null || e.date().isBefore(f.from()))) {
            return false;
        }
        if (f.to() != null && (e.date() == null || e.date().isAfter(f.to()))) {
            return false;
        }
        if (f.q() != null) {
            return contains(e.rawDescription(), f.q()) || contains(e.description(), f.q())
                || contains(e.comment(), f.q()) || contains(e.externalId(), f.q())
                || contains(e.receipt(), f.q()) || contains(e.transferKey(), f.q());
        }
        return true;
    }

    private static boolean contains(String field, String lowerNeedle) {
        return field != null && field.toLowerCase().contains(lowerNeedle);
    }
}
