package trex.v2.egress.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The byte mirror (V2-PROPOSAL.md §14): a second copy, byte for byte, verified. */
class ArchiveMirrorTest {

    @Test
    void copiesThenAppendsOnlyTheNewBytes(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("trex.jsonl");
        Path archive = dir.resolve("archive.jsonl");
        Files.writeString(source, "line one\n");

        ArchiveMirror.Result first = ArchiveMirror.mirrorJournal(source, archive);
        assertTrue(first.copied());
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(archive));

        Files.writeString(source, "line two\n", StandardOpenOption.APPEND);
        ArchiveMirror.Result second = ArchiveMirror.mirrorJournal(source, archive);
        assertTrue(second.copied());
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(archive));

        ArchiveMirror.Result third = ArchiveMirror.mirrorJournal(source, archive);
        assertFalse(third.copied(), "already up to date");
    }

    @Test
    void refusesWhenTheArchiveHasDiverged(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("trex.jsonl");
        Path archive = dir.resolve("archive.jsonl");
        Files.writeString(source, "aaaaa\n");
        Files.writeString(archive, "bbbbb\n");
        assertThrows(java.io.IOException.class, () -> ArchiveMirror.mirrorJournal(source, archive));
    }

    @Test
    void copiesEvidenceItDoesNotHave(@TempDir Path dir) throws Exception {
        Path evidence = dir.resolve("evidence");
        Files.createDirectories(evidence.resolve("ab"));
        Files.writeString(evidence.resolve("ab/cd.gz"), "x");
        Path target = dir.resolve("archive-evidence");
        assertEquals(1, ArchiveMirror.copyEvidence(evidence, target));
        assertEquals(0, ArchiveMirror.copyEvidence(evidence, target), "nothing new the second time");
        assertTrue(Files.exists(target.resolve("ab/cd.gz")));
    }
}
