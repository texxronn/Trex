package trex.v2.ingest;

import java.util.ArrayList;
import java.util.List;

/** A small RFC-4180-ish CSV reader for the bank exports: quotes, doubled quotes, embedded newlines. */
public final class Csv {

    public record Record(int line, List<String> fields) {}

    public static final class CsvException extends RuntimeException {
        private final int line;

        public CsvException(int line, String message) {
            super(message);
            this.line = line;
        }

        public int line() {
            return line;
        }
    }

    private Csv() {}

    public static List<Record> parse(String content) {
        List<Record> out = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int recordStart = 1;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
            } else {
                switch (c) {
                    case '"' -> quoted = true;
                    case ',' -> {
                        fields.add(field.toString());
                        field.setLength(0);
                    }
                    case '\r' -> { /* tolerate CRLF */ }
                    case '\n' -> {
                        fields.add(field.toString());
                        field.setLength(0);
                        out.add(new Record(recordStart, List.copyOf(fields)));
                        fields.clear();
                        line++;
                        recordStart = line;
                    }
                    default -> field.append(c);
                }
            }
        }
        if (quoted) {
            throw new CsvException(recordStart, "unterminated quoted field");
        }
        if (!fields.isEmpty() || field.length() > 0) {
            fields.add(field.toString());
            out.add(new Record(recordStart, List.copyOf(fields)));
        }
        return out;
    }
}
