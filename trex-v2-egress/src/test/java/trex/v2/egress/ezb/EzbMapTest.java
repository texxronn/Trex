package trex.v2.egress.ezb;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code deploy/config/ezb.yaml}: ref -> name + ezbookkeeping account category, plus the view's zone. */
class EzbMapTest {

    private static Path yaml(String body) throws Exception {
        Path dir = Files.createTempDirectory("ezbtest");
        Path file = dir.resolve("ezb.yaml");
        Files.writeString(file, body);
        return file;
    }

    @Test
    void loadsTheZoneAndEveryAccount() throws Exception {
        EzbMap map = EzbMap.load(yaml("""
            zone: "Australia/Sydney"
            accounts:
              ing-savings: { name: "ING Savings",         category: 8 }
              ing-orange:  { name: "ING Orange Everyday", category: 2 }
              ing-card:    { name: "ING Credit Card",     category: 3 }
            """));
        assertEquals(ZoneId.of("Australia/Sydney"), map.zone());
        assertEquals(new EzbMap.Entry("ING Savings", 8), map.get("ing-savings"));
        assertEquals(new EzbMap.Entry("ING Credit Card", 3), map.get("ing-card"));
        assertEquals(Set.of("ing-savings", "ing-orange", "ing-card"), map.refs());
    }

    @Test
    void everyCategoryFromTheRealFileIsInRange() {
        // The shipped file is the one this map is for; loading it must not throw.
        // Surefire's working directory is the module basedir (trex-v2-egress).
        EzbMap map = EzbMap.load(Path.of("..", "deploy", "config", "ezb.yaml").toAbsolutePath().normalize());
        assertTrue(map.refs().size() >= 20);
        assertEquals(ZoneId.of("Australia/Sydney"), map.zone());
    }

    @Test
    void rejectsAMissingZoneNamingTheFile() throws Exception {
        Path file = yaml("""
            accounts:
              ing-savings: { name: "ING Savings", category: 8 }
            """);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EzbMap.load(file));
        assertTrue(e.getMessage().contains("ezb.yaml"), e.getMessage());
        assertTrue(e.getMessage().contains("zone"), e.getMessage());
    }

    @Test
    void rejectsABlankOrUnknownZone() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> EzbMap.load(yaml("zone: \"  \"\naccounts:\n  a: { name: \"A\", category: 1 }\n")));
        assertThrows(IllegalArgumentException.class,
            () -> EzbMap.load(yaml("zone: \"Mars/Olympus\"\naccounts:\n  a: { name: \"A\", category: 1 }\n")));
    }

    @Test
    void rejectsABlankNameNamingTheRef() throws Exception {
        Path file = yaml("""
            zone: "Australia/Sydney"
            accounts:
              ing-savings: { name: " ", category: 8 }
            """);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EzbMap.load(file));
        assertTrue(e.getMessage().contains("ezb.yaml"), e.getMessage());
        assertTrue(e.getMessage().contains("ing-savings"), e.getMessage());
    }

    @Test
    void rejectsACategoryOutsideOneToNine() throws Exception {
        Path zero = yaml("""
            zone: "Australia/Sydney"
            accounts:
              ing-savings: { name: "ING Savings", category: 0 }
            """);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EzbMap.load(zero));
        assertTrue(e.getMessage().contains("ezb.yaml"), e.getMessage());
        assertTrue(e.getMessage().contains("ing-savings"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> EzbMap.load(yaml("""
            zone: "Australia/Sydney"
            accounts:
              ing-savings: { name: "ING Savings", category: 10 }
            """)));
    }

    @Test
    void rejectsAnEmptyOrAbsentAccountsMap() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> EzbMap.load(yaml("zone: \"Australia/Sydney\"\naccounts: {}\n")));
        assertThrows(IllegalArgumentException.class, () -> EzbMap.load(yaml("zone: \"Australia/Sydney\"\n")));
    }

    @Test
    void missingIsDeduplicatedAndSorted() throws Exception {
        EzbMap map = EzbMap.load(yaml("""
            zone: "Australia/Sydney"
            accounts:
              ing-savings: { name: "ING Savings", category: 8 }
            """));
        List<String> missing = map.missing(Arrays.asList("ing-savings", "zzz", "aaa", "zzz", null));
        assertEquals(List.of("aaa", "zzz"), missing);
    }
}
