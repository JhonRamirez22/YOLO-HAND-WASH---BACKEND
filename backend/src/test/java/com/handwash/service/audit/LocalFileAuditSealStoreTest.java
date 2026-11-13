package com.handwash.service.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Seam: AuditSealStore. A seal is written once and never replaced, and keys cannot escape the seal directory. */
class LocalFileAuditSealStoreTest {
    @TempDir Path dir;

    @Test
    void writesTheSealUnderItsKeyAndReportsItAsStoredOutsideTheDatabase() throws Exception {
        var store = new LocalFileAuditSealStore(dir);
        byte[] seal = "{\"head\":\"abc\"}".getBytes(StandardCharsets.UTF_8);

        store.put("seals/2026-10-26.json", seal);

        assertArrayEquals(seal, Files.readAllBytes(dir.resolve("seals/2026-10-26.json")));
        assertTrue(store.durable());
    }

    @Test
    void aSealIsNeverOverwritten() {
        var store = new LocalFileAuditSealStore(dir);
        store.put("seals/day.json", "one".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class, () -> store.put("seals/day.json", "two".getBytes(StandardCharsets.UTF_8)));
        assertEquals("one", readString(dir.resolve("seals/day.json")));
    }

    @Test
    void keysThatEscapeTheDirectoryAreRefused() {
        var store = new LocalFileAuditSealStore(dir);
        for (String bad : new String[] {"../escape.json", "seals/../../escape.json", "/etc/passwd", "seals\\..\\x.json",
            "", "seals/", "seals/a\0b.json"}) {
            assertThrows(IllegalArgumentException.class, () -> store.put(bad, new byte[] {1}), bad);
        }
        assertFalse(Files.exists(dir.getParent().resolve("escape.json")));
    }

    @Test
    void theSealIsMadeReadOnlyWhereTheFilesystemAllowsIt() throws Exception {
        var store = new LocalFileAuditSealStore(dir);
        store.put("seals/ro.json", new byte[] {1});
        assertFalse(Files.isWritable(dir.resolve("seals/ro.json")) && Files.getFileStore(dir).supportsFileAttributeView("posix"),
            "sealed files should not be writable");
    }

    private static String readString(Path path) {
        try { return Files.readString(path); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
}
