package trex.journal;

import trex.category.Categorizer;
import trex.category.CategoryRules;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reading {@code categories.yaml} and {@code pins.yaml} from disk. SPEC §5.6, §6.
 * <p>
 * The evaluator and every validation rule live in {@code trex.category} inside trex-core, which
 * may not touch the filesystem (§1). Only the two lines that actually read a file are here — and
 * they are here rather than anywhere else because this module already owns config binding: one
 * strictly-configured YAML mapper, so an unknown key is a startup error in every file trex reads,
 * not just most of them.
 * <p>
 * It could not simply have stayed beside the evaluator. trex-journal depends on trex-core, so a
 * trex-core class importing the YAML mapper would close a cycle Maven rejects. That constraint is
 * what produced the split, and the split turned out to be the better shape: <b>loading</b> is
 * shared by every consumer and lives here; <b>writing</b> belongs to trex-ws alone (§5.7), which
 * is why {@code RuleStore} is not in this module either.
 */
public final class RuleFiles {

    private RuleFiles() {}

    /** Rules only, no pins — for a fixture or a consumer that has none. */
    public static Categorizer load(Path categoriesFile) {
        return load(categoriesFile, null);
    }

    /**
     * @param pinsFile {@code pins.yaml}, or null. A missing file is not an error: pins are optional
     *                 by nature and the file does not exist until the first one is written (§5.7).
     */
    public static Categorizer load(Path categoriesFile, Path pinsFile) {
        CategoryRules.File parsed = Yaml.read(categoriesFile, CategoryRules.File.class);
        boolean havePins = pinsFile != null && Files.exists(pinsFile);
        CategoryRules.PinsFile pins =
            havePins ? Yaml.read(pinsFile, CategoryRules.PinsFile.class) : null;
        return CategoryRules.build(
            categoriesFile.getFileName().toString(), parsed,
            havePins ? pinsFile.getFileName().toString() : null, pins);
    }
}
