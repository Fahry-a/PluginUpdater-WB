package me.webbeck.pluginUpdater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JarHasherTest {

    @TempDir
    Path tempDir;

    @Test
    void hashesMatchMessageDigest() throws Exception {
        Path file = tempDir.resolve("a.jar");
        byte[] content = "fake-jar-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(file, content);

        java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
        StringBuilder expected = new StringBuilder();
        for (byte b : sha1.digest(content)) expected.append(String.format("%02x", b));

        assertEquals(expected.toString(), JarHasher.sha1(file));
        // Second call hits the cache and must agree.
        assertEquals(expected.toString(), JarHasher.sha1(file));
        assertEquals(64, JarHasher.sha256(file).length());
    }

    @Test
    void matchesHelpers() throws Exception {
        Path file = tempDir.resolve("b.jar");
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8));
        assertTrue(JarHasher.matchesSha1(file, JarHasher.sha1(file)));
        assertFalse(JarHasher.matchesSha1(file, "deadbeef"));
        assertFalse(JarHasher.matchesSha1(file, null));
        assertFalse(JarHasher.matchesSha1(tempDir.resolve("missing.jar"), "deadbeef"));
    }
}
