package trex.v2.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.Registry;
import trex.v2.core.config.User;
import trex.v2.hub.api.RefdataResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mortgage facility declaration (V2-SPEC.md §12): a view-config file the hub reads for the
 * Mortgage mode. Loads and validates account refs; a missing file is empty, and an unknown account,
 * a loan equal to its offset, or a bad state is a load error.
 */
class MortgagesTest {

    private static final Registry REGISTRY = new Registry(
        Map.of(
            "ing-loan-offset", account("ing-loan-offset"),
            "ing-mortgage-simplifier", account("ing-mortgage-simplifier"),
            "ing-variable-rate", account("ing-variable-rate")),
        Map.of("ron", new User("ron", "Ron", true, "weekly")));

    private static Account account(String ref) {
        return new Account(ref, "AUD", BalanceSource.STATEMENT, 7);
    }

    @Test
    void absentFileIsNoFacilities(@TempDir Path dir) {
        assertTrue(Mortgages.load(dir, REGISTRY).isEmpty());
    }

    @Test
    void loadsFacilitiesWithDefaults(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mortgages.yaml"), """
            mortgages:
              - name: "ING"
                offset: ing-loan-offset
                offsetAppliesTo: ing-variable-rate
                observedRatePct: 6.15
                loans:
                  - account: ing-mortgage-simplifier
                    label: "Simplifier"
                    repaymentCommitment: ing-simplifier-repayment
                    interestCommitment: ing-simplifier-interest
                  - account: ing-variable-rate
                    label: "Variable"
                    repaymentCommitment: ing-variable-repayment
                    interestCommitment: ing-variable-interest
                    state: closed
            """);
        List<RefdataResponse.MortgageJson> facilities = Mortgages.load(dir, REGISTRY);
        assertEquals(1, facilities.size());
        RefdataResponse.MortgageJson ing = facilities.getFirst();
        assertEquals("ING", ing.name());
        assertEquals("ing-loan-offset", ing.offset());
        assertEquals("ing-variable-rate", ing.offsetAppliesTo());
        assertEquals(6.15, ing.observedRatePct());
        assertEquals(2, ing.loans().size());
        assertEquals("open", ing.loans().get(0).state(), "state defaults to open");
        assertEquals("ing-simplifier-repayment", ing.loans().get(0).repaymentCommitment());
        assertEquals("closed", ing.loans().get(1).state());
    }

    @Test
    void offsetAppliesToMustNameALoan(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mortgages.yaml"), """
            mortgages:
              - name: "ING"
                offset: ing-loan-offset
                offsetAppliesTo: ing-credit-card
                loans:
                  - account: ing-variable-rate
                    repaymentCommitment: a
                    interestCommitment: b
            """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> Mortgages.load(dir, REGISTRY));
        assertTrue(error.getMessage().contains("offsetAppliesTo"), error.getMessage());
    }

    @Test
    void unknownAccountIsAnError(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mortgages.yaml"), """
            mortgages:
              - name: "ING"
                offset: ing-nope
                loans:
                  - account: ing-variable-rate
                    repaymentCommitment: a
                    interestCommitment: b
            """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> Mortgages.load(dir, REGISTRY));
        assertTrue(error.getMessage().contains("ing-nope"), error.getMessage());
    }

    @Test
    void loanEqualToItsOffsetIsAnError(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mortgages.yaml"), """
            mortgages:
              - name: "ING"
                offset: ing-loan-offset
                loans:
                  - account: ing-loan-offset
                    repaymentCommitment: a
                    interestCommitment: b
            """);
        assertThrows(IllegalArgumentException.class, () -> Mortgages.load(dir, REGISTRY));
    }

    @Test
    void unknownStateIsAnError(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mortgages.yaml"), """
            mortgages:
              - name: "ING"
                offset: ing-loan-offset
                loans:
                  - account: ing-variable-rate
                    repaymentCommitment: a
                    interestCommitment: b
                    state: dormant
            """);
        assertThrows(IllegalArgumentException.class, () -> Mortgages.load(dir, REGISTRY));
    }
}
