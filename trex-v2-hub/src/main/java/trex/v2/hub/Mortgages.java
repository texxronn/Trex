package trex.v2.hub;

import trex.v2.core.config.Account;
import trex.v2.core.config.Registry;
import trex.v2.hub.api.RefdataResponse;
import trex.v2.log.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The operator's mortgage facilities (V2-SPEC.md §12), read once at hub startup from an optional
 * {@code mortgages.yaml} under the config directory and served by {@code /api/refdata}.
 *
 * <p>This is <strong>view/hub config only</strong>. It links an offset account to the loans it
 * shelters and names the declared commitments that classify each loan's repayments and interest — a
 * declaration nothing in the read model can infer. It never reaches {@link trex.v2.log.ConfigLoader}
 * and is not hashed into {@code configRevision}: adding or editing it moves no fact, category,
 * transfer or derived table, and a rebuild and the state hash are unchanged. The file is a view
 * input, not a derive input, so it is loaded by the hub alone.
 *
 * <p>An absent file is valid (no facilities). Account refs are validated against the registry here,
 * so an unknown ref is a startup error naming the file. Commitment ids are decisions, not config, so
 * they are not validated against the log — an absent or retired commitment simply yields an empty
 * series in the view.
 */
final class Mortgages {

    /** {@code state} values the view understands; anything else is a load error. */
    private static final Set<String> STATES = Set.of("open", "closed");

    private Mortgages() {}

    /**
     * Read the declared facilities, or an empty list when the file is absent. Throws
     * {@link IllegalArgumentException} (a startup error) on a malformed file or an unknown account.
     */
    static List<RefdataResponse.MortgageJson> load(Path configDir, Registry registry) {
        Path file = configDir.resolve("mortgages.yaml");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        MortgagesFile parsed = Yaml.read(file, MortgagesFile.class);
        if (parsed.mortgages() == null) {
            return List.of();
        }
        List<RefdataResponse.MortgageJson> out = new ArrayList<>(parsed.mortgages().size());
        for (MortgageEntry m : parsed.mortgages()) {
            out.add(loadFacility(file, m, registry));
        }
        return List.copyOf(out);
    }

    private static RefdataResponse.MortgageJson loadFacility(Path file, MortgageEntry m, Registry registry) {
        if (m.name() == null || m.name().isBlank()) {
            throw new IllegalArgumentException(file.getFileName() + ": a facility has no name");
        }
        if (m.offset() == null || m.offset().isBlank()) {
            throw new IllegalArgumentException(file.getFileName() + ": facility '" + m.name()
                + "' has no offset account");
        }
        requireKnown(file, registry, m.offset());
        if (m.loans() == null || m.loans().isEmpty()) {
            throw new IllegalArgumentException(file.getFileName() + ": facility '" + m.name()
                + "' declares no loans");
        }
        List<RefdataResponse.LoanJson> loans = new ArrayList<>(m.loans().size());
        for (LoanEntry l : m.loans()) {
            if (l.account() == null || l.account().isBlank()) {
                throw new IllegalArgumentException(file.getFileName() + ": facility '" + m.name()
                    + "' has a loan with no account");
            }
            requireKnown(file, registry, l.account());
            if (l.account().equals(m.offset())) {
                throw new IllegalArgumentException(file.getFileName() + ": facility '" + m.name()
                    + "' names '" + l.account() + "' as both its offset and a loan");
            }
            if (l.repaymentCommitment() == null || l.repaymentCommitment().isBlank()) {
                throw new IllegalArgumentException(file.getFileName() + ": loan '" + l.account()
                    + "' has no repaymentCommitment");
            }
            if (l.interestCommitment() == null || l.interestCommitment().isBlank()) {
                throw new IllegalArgumentException(file.getFileName() + ": loan '" + l.account()
                    + "' has no interestCommitment");
            }
            String state = l.state() == null || l.state().isBlank()
                ? "open" : l.state().trim().toLowerCase(Locale.ROOT);
            if (!STATES.contains(state)) {
                throw new IllegalArgumentException(file.getFileName() + ": loan '" + l.account()
                    + "' has state '" + l.state() + "' (expected open or closed)");
            }
            loans.add(new RefdataResponse.LoanJson(l.account(), l.label(),
                l.repaymentCommitment(), l.interestCommitment(), state));
        }
        return new RefdataResponse.MortgageJson(m.name(), m.offset(), m.observedRatePct(), List.copyOf(loans));
    }

    private static void requireKnown(Path file, Registry registry, String ref) {
        Account account = registry.accounts().get(ref);
        if (account == null) {
            throw new IllegalArgumentException(file.getFileName()
                + ": unknown account '" + ref + "'");
        }
    }

    // ---- YAML shapes -------------------------------------------------------------------------

    record MortgagesFile(List<MortgageEntry> mortgages) {}

    record MortgageEntry(String name, String offset, Double observedRatePct, List<LoanEntry> loans) {}

    record LoanEntry(String account, String label, String repaymentCommitment,
                     String interestCommitment, String state) {}
}
