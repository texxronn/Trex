package trex.gateway.grid;

import trex.core.EventState;
import trex.core.TypeHint;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Parsed and validated {@code GET /api/rows} parameters. SPEC §5.5. */
record GridQuery(View view, List<SortKey> sort, int page, int size, Long asOfN, Filters filters) {

    enum View { TRANSACTIONS, JOURNAL }

    record SortKey(String column, boolean descending) {}

    /**
     * Everything that selects rows (part of the cache key).
     *
     * @param sinceN only rows with {@code n > sinceN}. For a consumer that has already seen
     *               everything up to a point and wants what moved — the Firefly egress pages the
     *               whole journal otherwise, every poll, to find the handful of new units (§5.8).
     */
    record Filters(String account, EventState state, TypeHint type, String category,
                   LocalDate from, LocalDate to, String q, Long sinceN) {}

    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 500;

    static GridQuery parse(String rawQuery) {
        Map<String, String> p = params(rawQuery);
        View view = switch (p.getOrDefault("view", "transactions")) {
            case "transactions" -> View.TRANSACTIONS;
            case "journal" -> View.JOURNAL;
            default -> throw new IllegalArgumentException("view must be transactions or journal");
        };
        List<SortKey> sort = new ArrayList<>();
        String sortParam = p.getOrDefault("sort", "n:desc");
        for (String part : sortParam.split(",")) {
            String[] kv = part.split(":", -1);
            if (kv.length != 2 || !Columns.exists(kv[0]) || !(kv[1].equals("asc") || kv[1].equals("desc"))) {
                throw new IllegalArgumentException("invalid sort: " + part);
            }
            if (sort.stream().anyMatch(k -> k.column().equals(kv[0]))) {
                throw new IllegalArgumentException("duplicate sort column: " + kv[0]);
            }
            sort.add(new SortKey(kv[0], kv[1].equals("desc")));
        }
        int page = intParam(p, "page", 1);
        int size = intParam(p, "size", DEFAULT_SIZE);
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be 1.." + MAX_SIZE);
        }
        Long asOfN = null;
        if (blankToNull(p.get("asOfN")) != null) {
            try {
                asOfN = Long.parseLong(p.get("asOfN"));
            } catch (NumberFormatException _) {
                throw new IllegalArgumentException("asOfN must be an integer");
            }
        }
        if (asOfN != null && asOfN < 0) {
            throw new IllegalArgumentException("asOfN must be >= 0");
        }
        Long sinceN = null;
        if (blankToNull(p.get("sinceN")) != null) {
            try {
                sinceN = Long.parseLong(p.get("sinceN"));
            } catch (NumberFormatException _) {
                throw new IllegalArgumentException("sinceN must be an integer");
            }
            if (sinceN < 0) {
                throw new IllegalArgumentException("sinceN must be >= 0");
            }
        }
        Filters filters = new Filters(
            blankToNull(p.get("account")),
            enumParam(EventState.class, p.get("state"), "state"),
            enumParam(TypeHint.class, p.get("type"), "type"),
            blankToNull(p.get("category")),
            dateParam(p.get("from"), "from"),
            dateParam(p.get("to"), "to"),
            blankToNull(p.get("q")) == null ? null : p.get("q").strip().toLowerCase(),
            sinceN);
        return new GridQuery(view, List.copyOf(sort), page, size, asOfN, filters);
    }

    private static Map<String, String> params(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            if (map.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate parameter: " + key);
            }
        }
        return map;
    }

    private static int intParam(Map<String, String> p, String name, int dflt) {
        String v = p.get(name);
        if (v == null || v.isEmpty()) {
            return dflt;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException _) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private static <E extends Enum<E>> E enumParam(Class<E> type, String v, String name) {
        if (blankToNull(v) == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException _) {
            throw new IllegalArgumentException("invalid " + name + ": " + v);
        }
    }

    private static LocalDate dateParam(String v, String name) {
        if (blankToNull(v) == null) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException _) {
            throw new IllegalArgumentException(name + " must be YYYY-MM-DD");
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
