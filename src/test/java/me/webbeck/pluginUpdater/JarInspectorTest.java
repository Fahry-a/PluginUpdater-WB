package me.webbeck.pluginUpdater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class JarInspectorTest {

    @TempDir
    Path tempDir;

    private Path writeJar(String fileName, String descriptorName, String descriptorContent) throws Exception {
        Path jar = tempDir.resolve(fileName);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            if (descriptorName != null) {
                zip.putNextEntry(new ZipEntry(descriptorName));
                zip.write(descriptorContent.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void validPluginYml() throws Exception {
        Path jar = writeJar("EssentialsX-2.20.1.jar", "plugin.yml", "name: EssentialsX\nversion: 2.20.1\n");
        JarInspector.Inspection inspection = JarInspector.inspect(jar);
        assertTrue(inspection.valid, inspection.error);
        assertEquals("EssentialsX", inspection.pluginName);
        assertEquals("plugin.yml", inspection.descriptor);
    }

    @Test
    void paperPluginYmlTakesPrecedence() throws Exception {
        Path jar = tempDir.resolve("Multi-1.0.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("plugin.yml"));
            zip.write("name: Legacy\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("paper-plugin.yml"));
            zip.write("name: Modern\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        JarInspector.Inspection inspection = JarInspector.inspect(jar);
        assertTrue(inspection.valid, inspection.error);
        assertEquals("Modern", inspection.pluginName);
        assertEquals("paper-plugin.yml", inspection.descriptor);
    }

    @Test
    void missingDescriptorIsInvalid() throws Exception {
        Path jar = writeJar("BungeeCord-1.0.jar", null, null);
        JarInspector.Inspection inspection = JarInspector.inspect(jar);
        assertFalse(inspection.valid);
        assertNotNull(inspection.error);
    }

    @Test
    void htmlErrorPageIsInvalid() throws Exception {
        Path fake = tempDir.resolve("PluginX-2.0.jar");
        Files.writeString(fake, "<html><body>404 Not Found</body></html>");
        JarInspector.Inspection inspection = JarInspector.inspect(fake);
        assertFalse(inspection.valid);
    }

    @Test
    void quotedNameIsUnquoted() {
        assertEquals("My Plugin", JarInspector.extractName("name: \"My Plugin\"\nversion: 1\n"));
        assertEquals("MyPlugin", JarInspector.extractName("name: 'MyPlugin'\n"));
    }
}
