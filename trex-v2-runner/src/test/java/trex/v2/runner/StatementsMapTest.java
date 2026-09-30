package trex.v2.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementsMapTest {

    @Test
    void exactAndGlobMatch(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("statements.yaml"), """
            files:
              - { match: "Salary_Account.csv", sourceType: ing-csv, account: ing-salary }
              - { match: "Bankwest_*.csv", sourceType: bw-csv, account: bw-credit-card }
            """);
        StatementsMap map = StatementsMap.load(dir);
        assertEquals("ing-salary", map.resolve("Salary_Account.csv").orElseThrow().account());
        assertEquals("bw-credit-card",
            map.resolve("Bankwest_Transactions_full.csv").orElseThrow().account());
        assertTrue(map.resolve("Unknown.csv").isEmpty());
    }

    @Test
    void missingFileIsEmpty(@TempDir Path dir) {
        assertTrue(StatementsMap.load(dir).resolve("anything.csv").isEmpty());
    }

    @Test
    void matchesHelper() {
        assertTrue(StatementsMap.matches("a*.csv", "abc.csv"));
        assertTrue(StatementsMap.matches("A.csv", "a.csv"));
        assertFalse(StatementsMap.matches("a.csv", "b.csv"));
        assertFalse(StatementsMap.matches("a.csv", "a.csv.bak"));
    }
}
