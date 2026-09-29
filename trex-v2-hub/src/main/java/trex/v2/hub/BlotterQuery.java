package trex.v2.hub;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A blotter query (V2-PROPOSAL.md §10.2): SQL-backed filters, stable sort and paging. Parsed from
 * the request's query string; an unknown value is a 400, never a silently ignored filter. Values
 * are bound as parameters; only whitelisted column and direction names are ever concatenated.
 */
public record BlotterQuery(String account, String category, String leg, String direction,
                           LocalDate from, LocalDate to, String q, Long minAmount, Long maxAmount,
                           boolean hasReview, String sort, String order, int limit, int offset) {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    public static BlotterQuery parse(String rawQuery) {
        Map<String, String> p = params(rawQuery);
        String leg = value(p, "leg");
        if (leg != null && !leg.equals("MATCHED") && !leg.equals("HELD") && !leg.equals("EXTERNAL")) {
            throw new IllegalArgumentException("leg must be MATCHED, HELD or EXTERNAL");
        }
        String direction = value(p, "direction");
        if (direction != null && !direction.equals("in") && !direction.equals("out")) {
            throw new IllegalArgumentException("direction must be 'in' or 'out'");
        }
        String sort = value(p, "sort");
        if (sort == null) {
            sort = "date";
        }
        if (!HubSql.LEDGER_SORT.containsKey(sort)) {
            throw new IllegalArgumentException("sort must be one of " + HubSql.LEDGER_SORT.keySet());
        }
        String order = value(p, "order");
        if (order == null) {
            order = "desc";
        }
        if (!order.equals("asc") && !order.equals("desc")) {
            throw new IllegalArgumentException("order must be 'asc' or 'desc'");
        }
        int limit = intValue(p, "limit", DEFAULT_LIMIT);
        if (limit < 1 || limit > MAX_LIMIT) {
            limit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        }
        int offset = Math.max(intValue(p, "offset", 0), 0);
        boolean hasReview = "true".equalsIgnoreCase(value(p, "hasReview"));
        return new BlotterQuery(
            value(p, "account"), value(p, "category"), leg, direction,
            dateValue(p, "from"), dateValue(p, "to"), value(p, "q"),
            longValue(p, "minAmount"), longValue(p, "maxAmount"), hasReview, sort, order, limit, offset);
    }

    private static Map<String, String> params(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = decode(pair.substring(0, eq));
            String value = decode(pair.substring(eq + 1));
            if (!key.isBlank() && !value.isBlank()) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String value(Map<String, String> p, String key) {
        String v = p.get(key);
        return v == null || v.isBlank() ? null : v;
    }

    private static LocalDate dateValue(Map<String, String> p, String key) {
        String v = value(p, key);
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(key + " must be an ISO date, not '" + v + "'");
        }
    }

    private static Long longValue(Map<String, String> p, String key) {
        String v = value(p, key);
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number of cents, not '" + v + "'");
        }
    }

    private static int intValue(Map<String, String> p, String key, int fallback) {
        String v = value(p, key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, not '" + v + "'");
        }
    }
}
