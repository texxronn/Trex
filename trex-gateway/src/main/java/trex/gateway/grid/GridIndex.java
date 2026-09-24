package trex.gateway.grid;

import trex.category.Categorized;
import trex.category.Categorizer;
import trex.category.Transfers;
import trex.core.CanonicalEvent;
import trex.core.TypeHint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Answers grid queries against a pinned snapshot: filter, sort (ties by n ascending), page.
 * Each distinct (generation, view, asOfN, filters, sort) result is computed once and cached. SPEC §5.5.
 */
final class GridIndex {

    record Total(String currency, long amount, long count) {}

    /** What a row's category is, and why. Keyed by {@code n} in the response, never stored (SPEC §0.7). */
    record RowCategory(String category, String origin, String why) {
        static RowCategory of(Categorized c) {
            return new RowCategory(c.category(), c.origin().name(), c.explain());
        }
    }

    /**
     * Categories travel beside the rows rather than inside them: a journal line has no category
     * field (SPEC §0.7), and the response shape says so.
     */
    record Page(long asOfN, String view, int page, int size, int total, List<CanonicalEvent> rows,
                List<Total> totals, Map<Long, RowCategory> categories) {}

    private record Key(long generation, GridQuery.View view, long asOfN, GridQuery.Filters filters, List<GridQuery.SortKey> sort) {}

    private record Result(List<CanonicalEvent> rows, List<Total> totals, Map<Long, RowCategory> categories) {}

    private static final int CACHE_ENTRIES = 16;

    private final Categorizer categorizer;

    GridIndex(Categorizer categorizer) {
        this.categorizer = categorizer;
    }

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
        List<CanonicalEvent> rows = result.rows().subList(from, to);
        Map<Long, RowCategory> categories = new LinkedHashMap<>();
        rows.forEach(e -> categories.put(e.n(), result.categories().get(e.n())));
        return new Page(asOfN, q.view().name().toLowerCase(), q.page(), q.size(), result.rows().size(),
            rows, result.totals(), categories);
    }

    private Result compute(GridData data, Key key) {
        List<CanonicalEvent> pinned = prefix(data.lines(), key.asOfN());
        List<CanonicalEvent> base;
        if (key.view() == GridQuery.View.TRANSACTIONS) {
            Map<String, CanonicalEvent> latest = new LinkedHashMap<>();
            pinned.forEach(l -> latest.put(l.externalId(), l));
            base = new ArrayList<>(latest.values());
        } else {
            base = new ArrayList<>(pinned);
        }
        // Categories are derived here, once per cached result, over the same pinned snapshot the
        // rows come from — so a category can never disagree with the row it is shown against.
        Map<String, CanonicalEvent> latestInSnapshot = new LinkedHashMap<>();
        pinned.forEach(l -> latestInSnapshot.put(l.externalId(), l));
        Set<String> transfers = Transfers.ids(List.copyOf(latestInSnapshot.values()));
        Map<Long, RowCategory> categories = new HashMap<>();
        for (CanonicalEvent e : base) {
            categories.put(e.n(), RowCategory.of(categorizer.categorize(e, transfers)));
        }

        List<CanonicalEvent> rows = new ArrayList<>(base.size());
        for (CanonicalEvent e : base) {
            if (matches(e, key.filters()) && matchesCategory(categories.get(e.n()), key.filters())) {
                rows.add(e);
            }
        }
        Comparator<CanonicalEvent> order = null;
        for (GridQuery.SortKey s : key.sort()) {
            Comparator<CanonicalEvent> next = Columns.comparator(s, e -> categories.get(e.n()).category());
            order = order == null ? next : order.thenComparing(next);
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
        return new Result(List.copyOf(rows), totals, Map.copyOf(categories));
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

    /** Filtering on a derived value: exact name, including TRANSFER and UNCATEGORIZED. */
    private static boolean matchesCategory(RowCategory category, GridQuery.Filters f) {
        return f.category() == null || f.category().equals(category.category());
    }

    private static boolean matches(CanonicalEvent e, GridQuery.Filters f) {
        // In the transactions view this is the LATEST line's n, so a transaction whose state
        // changed comes back even though it was seen before — which is what a projector needs:
        // the change is the news, not the first sighting.
        if (f.sinceN() != null && e.n() <= f.sinceN()) {
            return false;
        }
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
