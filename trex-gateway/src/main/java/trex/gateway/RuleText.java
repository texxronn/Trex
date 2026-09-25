package trex.gateway;

import trex.category.CategoryRules;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * A rules file as <em>text</em>, so an amendment can change one entry and nothing else. SPEC §5.6.
 * <p>
 * The obvious implementation — parse the YAML, edit the object tree, write it back — cannot be
 * used here: the mapper cannot round-trip {@code #} comments, and 70 of {@code categories.yaml}'s
 * 218 lines are comments carrying the reasoning ("before BILLS, because a loan interest line names
 * the mortgage"). Re-serialising would delete all of it and reformat everything else, turning a
 * one-line change into an unreviewable diff.
 * <p>
 * So this works on lines. It finds an entry's line span by scanning, and splices. Every byte
 * outside the span it touches is preserved exactly, which is what makes a machine write safe on a
 * file a human also edits. The written result is always validated by loading it before it replaces
 * anything ({@code RuleStore} (trex-ws)), so a splice that produced nonsense cannot reach the running service.
 */
public final class RuleText {

    /** A YAML writer, separate from the strict reader in trex-journal: this one omits nulls. */
    private static final ObjectMapper WRITER = new ObjectMapper(
        YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            // Quote every string. A rule's `match` is a regex, and an unquoted one containing
            // '#' becomes a YAML comment, ':' becomes a mapping, and a leading '*' an alias.
            // Minimised quotes would read a little better and break on the first awkward pattern.
            .disable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build())
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /**
     * Where one entry lives in the file.
     *
     * @param index    1-based, matching {@link Rule#index()} and the load-time error messages
     * @param from     first line of the entry, <em>including</em> the comment block above it
     * @param bodyFrom the {@code - category:} line itself
     * @param to       one past the last line, excluding the blank line that follows
     */
    public record Span(int index, int from, int bodyFrom, int to) {}

    private final List<String> lines;
    private final String listKey;

    public RuleText(String text, String listKey) {
        // A trailing newline would otherwise become a phantom final element.
        this.lines = new ArrayList<>(List.of(text.split("\n", -1)));
        this.listKey = listKey;
    }

    public String text() {
        return String.join("\n", lines);
    }

    /**
     * The entries under {@code listKey}, in file order.
     * <p>
     * An entry's span starts at the comment block directly above it — contiguous {@code #} lines
     * at the entry's own indent with no blank line between. Those comments explain <em>that</em>
     * rule, so deleting the rule must delete them too, and inserting before it must land above
     * them rather than between a comment and the rule it describes.
     */
    public List<Span> spans() {
        int listLine = indexOfKey();
        if (listLine < 0) {
            return List.of();
        }
        // Pass one: where each entry's `- category:` line is, and where the list stops.
        List<Integer> bodies = new ArrayList<>();
        Integer itemIndent = null;
        int listEnd = lines.size();
        for (int i = listLine + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isBlank() && indentOf(line) == 0) {
                listEnd = i;
                break;
            }
            if (isItemStart(line, itemIndent)) {
                if (itemIndent == null) {
                    itemIndent = indentOf(line);
                }
                bodies.add(i);
            }
        }
        // Pass two: an entry runs from its own comment block to the start of the NEXT entry's
        // comment block. Getting this wrong the first time made a replace eat the following
        // rule's comment, which is exactly the kind of damage this class exists to prevent.
        List<Span> out = new ArrayList<>();
        for (int n = 0; n < bodies.size(); n++) {
            int bodyFrom = bodies.get(n);
            int nextStart = n + 1 < bodies.size() ? commentStart(bodies.get(n + 1)) : listEnd;
            out.add(new Span(n + 1, commentStart(bodyFrom), bodyFrom, trimBlank(nextStart)));
        }
        return out;
    }

    /** Insert rendered text before the entry at {@code index}, or at the end when it is null. */
    public void insert(Integer beforeIndex, String entryText) {
        List<Span> spans = spans();
        int at;
        if (beforeIndex == null || spans.isEmpty() || beforeIndex > spans.size()) {
            at = spans.isEmpty() ? afterListKey() : spans.getLast().to();
        } else {
            at = spans.get(beforeIndex - 1).from();
        }
        List<String> block = new ArrayList<>(List.of(entryText.split("\n", -1)));
        if (!block.isEmpty() && block.getLast().isEmpty()) {
            block.removeLast();
        }
        block.add("");                       // one blank line, matching how the file already reads
        if (at > 0 && !lines.get(at - 1).isBlank()) {
            block.addFirst("");              // and one before it, when appending after an entry
        }
        lines.addAll(at, block);
    }

    /** Remove the entry at {@code index}, with the comments that explain it. */
    public void delete(int index) {
        Span span = span(index);
        lines.subList(span.from(), skipTrailingBlank(span.to())).clear();
    }

    /** Replace the entry at {@code index}, keeping the comment block above it. */
    public void replace(int index, String entryText) {
        Span span = span(index);
        List<String> block = new ArrayList<>(List.of(entryText.split("\n", -1)));
        if (!block.isEmpty() && block.getLast().isEmpty()) {
            block.removeLast();
        }
        lines.subList(span.bodyFrom(), span.to()).clear();
        lines.addAll(span.bodyFrom(), block);
    }

    private Span span(int index) {
        return spans().stream()
            .filter(s -> s.index() == index)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                listKey + " has no entry #" + index + " (it has " + spans().size() + ")"));
    }

    /**
     * One entry as YAML, at the indentation the shipped files use: two spaces for the {@code -},
     * four for its fields. Rendered from the bound record rather than from strings, so a value
     * that needs quoting gets quoted by the mapper rather than by hope.
     */
    public static String render(CategoryRules.RuleEntry entry) {
        try {
            String yaml = WRITER.writeValueAsString(entry).strip();
            List<String> out = new ArrayList<>();
            boolean first = true;
            for (String line : yaml.split("\n")) {
                out.add((first ? "  - " : "    ") + line);
                first = false;
            }
            return String.join("\n", out);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("cannot render the entry: " + e.getOriginalMessage(), e);
        }
    }

    // ---------------------------------------------------------------- scanning

    private int indexOfKey() {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (indentOf(line) == 0 && line.stripTrailing().equals(listKey + ":")) {
                return i;
            }
            // `pins: []` — an empty inline list is the key with nothing under it.
            if (indentOf(line) == 0 && line.stripTrailing().equals(listKey + ": []")) {
                return i;
            }
        }
        return -1;
    }

    /** Where a first entry goes when the list is empty: replacing `key: []` with `key:`. */
    private int afterListKey() {
        int at = indexOfKey();
        if (at < 0) {
            throw new IllegalArgumentException("no '" + listKey + ":' key in the file");
        }
        if (lines.get(at).stripTrailing().endsWith("[]")) {
            lines.set(at, listKey + ":");
        }
        return at + 1;
    }

    private boolean isItemStart(String line, Integer itemIndent) {
        String stripped = line.strip();
        if (!stripped.startsWith("- ")) {
            return false;
        }
        return itemIndent == null || indentOf(line) == itemIndent;
    }

    /** Walk up over the comment lines that belong to this entry. */
    private int commentStart(int bodyFrom) {
        int at = bodyFrom;
        while (at - 1 > 0 && lines.get(at - 1).strip().startsWith("#")) {
            at--;
        }
        return at;
    }

    /** An entry ends at its last content line; the blank line after it separates, it does not belong. */
    private int trimBlank(int end) {
        int at = end;
        while (at - 1 > 0 && lines.get(at - 1).isBlank()) {
            at--;
        }
        return at;
    }

    /** On delete, the separating blank line goes too, or the file grows gaps over time. */
    private int skipTrailingBlank(int to) {
        int at = to;
        while (at < lines.size() && lines.get(at).isBlank()) {
            at++;
        }
        return at;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }
}
