package trex.category;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * The two rule files and the only safe way to change them. SPEC §5.6, §5.7.
 * <p>
 * Every write goes through the same three steps, in this order:
 * <ol>
 *   <li><b>Check the revision.</b> A write names the revision it was composed against. If the file
 *       has moved since — someone edited it, or another write landed — the write is refused rather
 *       than applied to a file that is no longer the one it was reasoned about.</li>
 *   <li><b>Splice the text</b> ({@link RuleText}), so comments, ordering and every untouched byte
 *       survive and the diff is the change itself.</li>
 *   <li><b>Validate before swap.</b> The new text goes to a temp file, is loaded through
 *       {@link CategoryRules}, and only replaces the original if it loads. This is the sequencer's
 *       validate-all-then-commit (§3.3) applied to config: a rule set that would fail at load never
 *       replaces one that works, so a bad amendment cannot take the service down on reload.</li>
 * </ol>
 * The swap itself is an atomic rename, as the journal does, so a reader never sees half a file.
 */
public final class RuleStore {

    private static final Logger log = LoggerFactory.getLogger(RuleStore.class);

    private final Path categoriesFile;
    private final Path pinsFile;

    public RuleStore(Path categoriesFile, Path pinsFile) {
        this.categoriesFile = categoriesFile;
        this.pinsFile = pinsFile;
    }

    /** Refused because the file moved under the write. Carries the revision it actually has. */
    public static final class RevisionConflict extends RuntimeException {
        private final String actual;

        RevisionConflict(String expected, String actual) {
            super("the rules changed since this was composed (expected " + expected + ", found " + actual + ")");
            this.actual = actual;
        }

        public String actual() {
            return actual;
        }
    }

    /** Refused because the amended file would not load; nothing was written. */
    public static final class Invalid extends RuntimeException {
        Invalid(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public Path categoriesFile() {
        return categoriesFile;
    }

    public Path pinsFile() {
        return pinsFile;
    }

    public Categorizer load() {
        return CategoryRules.load(categoriesFile, pinsFile);
    }

    /**
     * The entries as written, {@code when} trees included.
     * <p>
     * The compiled {@link Rule} deliberately does not keep its source: it holds a compiled
     * {@link Condition}, which is what matching needs and is a different shape from what the file
     * says. A client that wants to edit a rule needs the file's shape back — otherwise it can
     * replace a rule only by retyping it — so this reads the file rather than reconstructing it.
     */
    public List<CategoryRules.RuleEntry> rules() {
        return orEmpty(Files.exists(categoriesFile)
            ? trex.journal.Yaml.read(categoriesFile, CategoryRules.File.class).rules() : null);
    }

    public List<CategoryRules.RuleEntry> pins() {
        return orEmpty(pinsFile != null && Files.exists(pinsFile)
            ? trex.journal.Yaml.read(pinsFile, CategoryRules.PinsFile.class).pins() : null);
    }

    private static List<CategoryRules.RuleEntry> orEmpty(List<CategoryRules.RuleEntry> entries) {
        return entries == null ? List.of() : entries;
    }

    /**
     * A hash of both files' bytes, short enough to put in a payload and read aloud.
     * <p>
     * Both, not one each: a consumer asking "which rules produced this?" means the pair, since a
     * pin in one overrides a rule in the other. It appears in every snapshot and every event frame,
     * and a write names the revision it was composed against (§5.7).
     */
    public String revision() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(readOrEmpty(categoriesFile));
            sha.update((byte) '\n');
            sha.update(readOrEmpty(pinsFile));
            return HexFormat.of().formatHex(sha.digest(), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /** Insert a rule, at a computed position or appended when {@code beforeIndex} is null. */
    public String addRule(CategoryRules.RuleEntry entry, Integer beforeIndex, String expectedRevision) {
        return amend(categoriesFile, "rules", expectedRevision,
            text -> text.insert(beforeIndex, RuleText.render(entry)));
    }

    /** Append a pin. Position is meaningless for pins: they match exact ids, so at most one fires. */
    public String addPin(CategoryRules.RuleEntry entry, String expectedRevision) {
        return amend(pinsFile, "pins", expectedRevision,
            text -> text.insert(null, RuleText.render(entry)));
    }

    public String replaceRule(int index, CategoryRules.RuleEntry entry, String expectedRevision) {
        return amend(categoriesFile, "rules", expectedRevision,
            text -> text.replace(index, RuleText.render(entry)));
    }

    public String replacePin(int index, CategoryRules.RuleEntry entry, String expectedRevision) {
        return amend(pinsFile, "pins", expectedRevision, text -> text.replace(index, RuleText.render(entry)));
    }

    public String deleteRule(int index, String expectedRevision) {
        return amend(categoriesFile, "rules", expectedRevision, text -> text.delete(index));
    }

    public String deletePin(int index, String expectedRevision) {
        return amend(pinsFile, "pins", expectedRevision, text -> text.delete(index));
    }

    /** The whole discipline, in one place, so no write path can skip a step. */
    private String amend(Path file, String listKey, String expectedRevision, java.util.function.Consumer<RuleText> edit) {
        String actual = revision();
        if (expectedRevision != null && !expectedRevision.equals(actual)) {
            throw new RevisionConflict(expectedRevision, actual);
        }

        RuleText text = new RuleText(readText(file), listKey);
        edit.accept(text);
        String amended = text.text();

        Path temp = file.resolveSibling(file.getFileName() + ".amend");
        try {
            Files.writeString(temp, amended, StandardCharsets.UTF_8);
            // The proof that the amendment is good is that it loads — including against the OTHER
            // file, since a pin is validated against the categories that file declares.
            try {
                if (file.equals(categoriesFile)) {
                    CategoryRules.load(temp, pinsFile);
                } else {
                    CategoryRules.load(categoriesFile, temp);
                }
            } catch (RuntimeException e) {
                throw new Invalid("the amended " + file.getFileName() + " would not load: " + e.getMessage(), e);
            }
            swap(temp, file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                log.warn("could not remove {}", temp, e);
            }
        }
        String after = revision();
        log.info("amended {}: revision {} -> {}", file.getFileName(), actual, after);
        return after;
    }

    private static void swap(Path temp, Path file) throws IOException {
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some bind mounts and network filesystems refuse it; a plain replace is still a
            // single rename on every filesystem we support.
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String readText(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** A pins file that does not exist yet hashes as empty rather than failing (§5.6). */
    private static byte[] readOrEmpty(Path file) {
        try {
            return file != null && Files.exists(file) ? Files.readAllBytes(file) : new byte[0];
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
