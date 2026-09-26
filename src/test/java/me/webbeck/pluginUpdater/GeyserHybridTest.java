package me.webbeck.pluginUpdater;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GeyserHybridTest {

    @TempDir
    Path tempDir;

    private static JsonObject asset(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("browser_download_url", "https://example.com/" + name);
        return o;
    }

    @Test
    void pinnedAssetWinsExactCaseInsensitive() {
        List<JsonObject> candidates = List.of(
                asset("MCXboxBroadcastStandalone.jar"),
                asset("MCXboxBroadcastExtension.jar"));
        JsonObject chosen = UpdateChecker.selectPinnedAsset(candidates, "mcxboxbroadcastextension.jar");
        assertNotNull(chosen);
        assertEquals("MCXboxBroadcastExtension.jar", chosen.get("name").getAsString());
    }

    @Test
    void pinnedAssetMissReturnsNullSoCallerScansOlderReleases() {
        List<JsonObject> candidates = List.of(asset("MCXboxBroadcastStandalone.jar"));
        assertNull(UpdateChecker.selectPinnedAsset(candidates, "MCXboxBroadcastExtension.jar"));
        assertNull(UpdateChecker.selectPinnedAsset(candidates, null));
        assertNull(UpdateChecker.selectPinnedAsset(candidates, "  "));
    }

    @Test
    void validatorsMatchPrefersEtagThenLastModified() {
        assertTrue(GeyserManager.validatorsMatch("\"abc\"", "today", "\"abc\"", "other"));
        assertFalse(GeyserManager.validatorsMatch("\"abc\"", "today", "\"xyz\"", "today"));
        assertTrue(GeyserManager.validatorsMatch(null, "today", null, "today"));
        assertFalse(GeyserManager.validatorsMatch(null, null, "\"abc\"", null));
        assertFalse(GeyserManager.validatorsMatch(null, null, null, null));
    }

    @Test
    void geyserNamesRecognised() {
        assertTrue(ConfigManager.isGeyserPluginName("Geyser"));
        assertTrue(ConfigManager.isGeyserPluginName("geyser-spigot"));
        assertFalse(ConfigManager.isGeyserPluginName("Floodgate"));
        assertFalse(ConfigManager.isGeyserPluginName(null));
    }

    @Test
    void geyserModrinthIdConstant() {
        assertEquals("wKkoqHrH", ConfigManager.GEYSER_MODRINTH_ID);
    }

    @Test
    void extensionWithoutDescriptorIsValidPluginIsNot() throws Exception {
        Path jar = tempDir.resolve("MCXboxBroadcastExtension.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\n".getBytes());
            zip.closeEntry();
        }
        assertTrue(JarInspector.inspectExtension(jar).valid);
        assertFalse(JarInspector.inspect(jar).valid);
    }

    @Test
    void extensionHtmlErrorPageIsInvalid() throws Exception {
        Path fake = tempDir.resolve("MCXboxBroadcastExtension.jar");
        Files.writeString(fake, "<html>oops</html>");
        assertFalse(JarInspector.inspectExtension(fake).valid);
    }

    @Test
    void customSnapshotDefaultsKeepOldBehavior() {
        PluginSnapshot snap = new PluginSnapshot("X", "MODRINTH", "id", null, null,
                List.of("release"), "1.0", "paper", "26.3", null, "1.0", null, null, null, null);
        assertTrue(snap.gameVersionFilter);
        assertNull(snap.githubAsset);
    }

    @Test
    void githubDigestParsing() {
        JsonObject asset = JsonParser.parseString(
                "{\"name\":\"MCXboxBroadcastExtension.jar\",\"digest\":\"sha256:d6a824915f1ef559cceecd4a55de0c710f02c03def6e47b0cbd79646c1ef921b\"}")
                .getAsJsonObject();
        String digest = asset.get("digest").getAsString();
        int colon = digest.indexOf(':');
        assertTrue(digest.toLowerCase().startsWith("sha256:") && colon > 0);
        assertEquals("d6a824915f1ef559cceecd4a55de0c710f02c03def6e47b0cbd79646c1ef921b",
                digest.substring(colon + 1));
    }
}
