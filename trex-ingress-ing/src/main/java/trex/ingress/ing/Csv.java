package trex.ingress.ing;

import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 reader: quoted fields, doubled quotes, CRLF/LF, embedded newlines. */
final class Csv {

    record Record(int line, List<String> fields) {}

    static final class CsvException extends RuntimeException {
        private final int line;

        CsvException(int line, String message) {
            super(message);
            this.line = line;
        }

        int line() {
            return line;
        }
    }

    private Csv() {}

    static List<Record> parse(String content) {
        List<Record> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        int line = 1;
        int recordLine = 1;
        boolean quoted = false;
        boolean fieldWasQuoted = false;
        int i = 0;
        while (i < content.length()) {
            char ch = content.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    quoted = false;
                } else {
                    if (ch == '\n') {
                        line++;
                    }
                    field.append(ch);
                }
                i++;
                continue;
            }
            switch (ch) {
                case '"' -> {
                    if (!field.isEmpty() || fieldWasQuoted) {
                        throw new CsvException(line, "unexpected quote inside field");
                    }
                    quoted = true;
                    fieldWasQuoted = true;
                }
                case ',' -> {
                    fields.add(field.toString());
                    field.setLength(0);
                    fieldWasQuoted = false;
                }
                case '\r' -> {
                    if (i + 1 >= content.length() || content.charAt(i + 1) != '\n') {
                        throw new CsvException(line, "bare carriage return");
                    }
                }
                case '\n' -> {
                    fields.add(field.toString());
                    records.add(new Record(recordLine, List.copyOf(fields)));
                    fields.clear();
                    field.setLength(0);
                    fieldWasQuoted = false;
                    line++;
                    recordLine = line;
                }
                default -> {
                    if (fieldWasQuoted) {
                        throw new CsvException(line, "content after closing quote");
                    }
                    field.append(ch);
                }
            }
            i++;
        }
        if (quoted) {
            throw new CsvException(recordLine, "unterminated quoted field");
        }
        if (!field.isEmpty() || !fields.isEmpty() || fieldWasQuoted) {
            fields.add(field.toString());
            records.add(new Record(recordLine, List.copyOf(fields)));
        }
        return records;
    }
}
