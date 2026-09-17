package trex.sequencer.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TomlTest {

    @Test
    void parsesSupportedSubset() {
        Map<String, Object> doc = Toml.parse("""
            # top comment
            apiPort = 8_080   # trailing comment
            enabled = true
            name = "a \\"quoted\\" \\u00e9 name"
            regex = 'Receipt (No )?(\\d+)'
            allowlist = [
              "Internal Transfer",   # comment inside array
              'To my account',
            ]

            [journal]
            source = "journal.jsonl"

            [[account]]
            ref = "ing-savings"

            [[account]]
            ref = "cba-everyday"
            """);
        assertEquals(8080L, doc.get("apiPort"));
        assertEquals(true, doc.get("enabled"));
        assertEquals("a \"quoted\" é name", doc.get("name"));
        assertEquals("Receipt (No )?(\\d+)", doc.get("regex"));
        assertEquals(List.of("Internal Transfer", "To my account"), doc.get("allowlist"));
        assertEquals(Map.of("source", "journal.jsonl"), doc.get("journal"));
        assertEquals(List.of(Map.of("ref", "ing-savings"), Map.of("ref", "cba-everyday")), doc.get("account"));
    }

    @Test
    void rejectsUnsupportedOrInvalidInput() {
        for (String bad : List.of(
            "a = 1.5",
            "a = 1\na = 2",
            "[t]\n[t]",
            "a.b = 1",
            "a = [1, 2]",
            "a = \"unterminated",
            "a = { x = 1 }",
            "a = 1 b = 2",
            "a = 2026-01-01")) {
            assertThrows(Toml.TomlException.class, () -> Toml.parse(bad), bad);
        }
    }

    @Test
    void errorsCarryLineNumbers() {
        Toml.TomlException e = assertThrows(Toml.TomlException.class, () -> Toml.parse("a = 1\n\nb = 1.5\n"));
        assertEquals("line 3: unexpected content after value", e.getMessage());
    }
}
