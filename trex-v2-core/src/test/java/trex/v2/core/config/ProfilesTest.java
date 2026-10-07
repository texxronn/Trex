package trex.v2.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Account profiles (V2-PROPOSAL.md §6.9): a rule is scoped to its account (or {@code *}), matches
 * case-insensitively and never asserts anything about an unlisted account.
 */
class ProfilesTest {

    @Test
    void rulesAreAccountScopedAndCaseInsensitive() {
        Profiles profiles = new Profiles(List.of(new Profiles.Rule("ing-variable-rate",
            Pattern.compile("orange advantage annual fee", Pattern.CASE_INSENSITIVE), "reference")));
        assertTrue(profiles.isNoop("ing-variable-rate", "Orange Advantage annual fee - Receipt No 900068"));
        assertFalse(profiles.isNoop("ing-mortgage-simplifier", "Orange Advantage annual fee - Receipt No 1"));
        assertFalse(profiles.isNoop("ing-variable-rate", "Repayment - Direct Credit - Receipt No 1"));
    }

    @Test
    void wildcardAppliesToEveryAccount() {
        Profiles profiles = new Profiles(List.of(new Profiles.Rule(Profiles.ANY_ACCOUNT,
            Pattern.compile("interest charge", Pattern.CASE_INSENSITIVE), "memo")));
        assertTrue(profiles.isNoop("anything", "Interest Charge - Receipt No 1"));
    }

    @Test
    void emptyNeverMatches() {
        assertFalse(Profiles.empty().isNoop("ing-variable-rate", "Orange Advantage annual fee"));
    }
}
