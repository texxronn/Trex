package trex.sequencer.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written TOML subset parser (SPEC §6): {@code [table]}, {@code [[array-of-tables]]},
 * {@code key = value} with basic/literal strings, integers, booleans, arrays of strings,
 * and {@code #} comments. Anything else is a config error.
 * Result: nested maps; values are String, Long, Boolean, List&lt;String&gt;; array-of-tables are List&lt;Map&gt;.
 */
public final class Toml {

    public static final class TomlException extends RuntimeException {
        TomlException(int line, String message) {
            super("line " + line + ": " + message);
        }
    }

    private final String text;
    private int pos;
    private int line = 1;

    private Toml(String text) {
        this.text = text;
    }

    public static Map<String, Object> parse(String text) {
        return new Toml(text).document();
    }

    private Map<String, Object> document() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        while (true) {
            skipWhitespaceAndNewlines();
            if (pos >= text.length()) {
                return root;
            }
            char ch = text.charAt(pos);
            if (ch == '[') {
                current = header(root);
            } else {
                keyValue(current);
            }
            endOfLine();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> header(Map<String, Object> root) {
        boolean arrayOfTables = text.startsWith("[[", pos);
        pos += arrayOfTables ? 2 : 1;
        skipSpaces();
        String name = bareKey();
        skipSpaces();
        String close = arrayOfTables ? "]]" : "]";
        if (!text.startsWith(close, pos)) {
            throw error("expected '" + close + "'");
        }
        pos += close.length();
        Map<String, Object> table = new LinkedHashMap<>();
        Object existing = root.get(name);
        if (arrayOfTables) {
            if (existing == null) {
                List<Map<String, Object>> list = new ArrayList<>();
                root.put(name, list);
                list.add(table);
            } else if (existing instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map) {
                ((List<Map<String, Object>>) list).add(table);
            } else {
                throw error("'" + name + "' is already defined");
            }
        } else {
            if (existing != null) {
                throw error("'" + name + "' is already defined");
            }
            root.put(name, table);
        }
        return table;
    }

    private void keyValue(Map<String, Object> table) {
        String key = bareKey();
        skipSpaces();
        if (pos >= text.length() || text.charAt(pos) != '=') {
            throw error("expected '=' after key '" + key + "'");
        }
        pos++;
        skipSpaces();
        Object value = value();
        if (table.putIfAbsent(key, value) != null) {
            throw error("duplicate key '" + key + "'");
        }
    }

    private Object value() {
        if (pos >= text.length()) {
            throw error("missing value");
        }
        char ch = text.charAt(pos);
        if (ch == '"' || ch == '\'') {
            return string();
        }
        if (ch == '[') {
            return stringArray();
        }
        if (text.startsWith("true", pos) && !bareKeyChar(pos + 4)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", pos) && !bareKeyChar(pos + 5)) {
            pos += 5;
            return Boolean.FALSE;
        }
        return integer();
    }

    private Long integer() {
        int start = pos;
        if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
            pos++;
        }
        int digitsStart = pos;
        while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
            pos++;
        }
        String raw = text.substring(start, pos);
        String digits = text.substring(digitsStart, pos);
        if (digits.isEmpty() || digits.startsWith("_") || digits.endsWith("_") || digits.contains("__")) {
            throw error("unsupported value");
        }
        try {
            return Long.parseLong(raw.replace("_", ""));
        } catch (NumberFormatException e) {
            throw error("invalid integer '" + raw + "'");
        }
    }

    private List<String> stringArray() {
        pos++;
        List<String> items = new ArrayList<>();
        while (true) {
            skipWhitespaceNewlinesAndComments();
            if (pos >= text.length()) {
                throw error("unterminated array");
            }
            if (text.charAt(pos) == ']') {
                pos++;
                return List.copyOf(items);
            }
            char ch = text.charAt(pos);
            if (ch != '"' && ch != '\'') {
                throw error("arrays may contain only strings");
            }
            items.add(string());
            skipWhitespaceNewlinesAndComments();
            if (pos < text.length() && text.charAt(pos) == ',') {
                pos++;
            } else if (pos < text.length() && text.charAt(pos) != ']') {
                throw error("expected ',' or ']' in array");
            }
        }
    }

    private String string() {
        char quote = text.charAt(pos++);
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length() || text.charAt(pos) == '\n') {
                throw error("unterminated string");
            }
            char ch = text.charAt(pos++);
            if (ch == quote) {
                return sb.toString();
            }
            if (ch == '\\' && quote == '"') {
                if (pos >= text.length()) {
                    throw error("unterminated string");
                }
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw error("invalid \\u escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("invalid \\u escape");
                        }
                        pos += 4;
                    }
                    default -> throw error("unsupported escape '\\" + esc + "'");
                }
            } else {
                sb.append(ch);
            }
        }
    }

    private String bareKey() {
        int start = pos;
        while (bareKeyChar(pos)) {
            pos++;
        }
        if (start == pos) {
            throw error("expected a bare key");
        }
        return text.substring(start, pos);
    }

    private boolean bareKeyChar(int i) {
        if (i >= text.length()) {
            return false;
        }
        char ch = text.charAt(i);
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '-';
    }

    private void endOfLine() {
        skipSpaces();
        if (pos < text.length() && text.charAt(pos) == '#') {
            skipComment();
        }
        if (pos < text.length()) {
            if (text.charAt(pos) == '\r' && pos + 1 < text.length() && text.charAt(pos + 1) == '\n') {
                pos++;
            }
            if (text.charAt(pos) != '\n') {
                throw error("unexpected content after value");
            }
        }
    }

    private void skipSpaces() {
        while (pos < text.length() && (text.charAt(pos) == ' ' || text.charAt(pos) == '\t')) {
            pos++;
        }
    }

    private void skipComment() {
        while (pos < text.length() && text.charAt(pos) != '\n') {
            pos++;
        }
    }

    private void skipWhitespaceAndNewlines() {
        skipWhitespaceNewlinesAndComments();
    }

    private void skipWhitespaceNewlinesAndComments() {
        while (pos < text.length()) {
            char ch = text.charAt(pos);
            if (ch == '\n') {
                line++;
                pos++;
            } else if (ch == ' ' || ch == '\t' || ch == '\r') {
                pos++;
            } else if (ch == '#') {
                skipComment();
            } else {
                return;
            }
        }
    }

    private TomlException error(String message) {
        return new TomlException(line, message);
    }
}
