package me.webbeck.pluginUpdater;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginUpdaterUtilsTest {

    @Test
    void equalVersionsMatch() {
        assertTrue(PluginUpdaterUtils.versionsMatch("1.0", "1.0.0"));
        assertTrue(PluginUpdaterUtils.versionsMatch("v1.2.3", "1.2.3"));
        assertTrue(PluginUpdaterUtils.versionsMatch("2.20.1", "2.20.1"));
    }

    @Test
    void newerRemoteIsDetected() {
        assertTrue(PluginUpdaterUtils.isNewerThan("1.0", "1.0.1"));
        assertTrue(PluginUpdaterUtils.isNewerThan("2.19.4", "2.20.1"));
        assertFalse(PluginUpdaterUtils.versionsMatch("1.0", "1.0.1"));
    }

    @Test
    void olderRemoteIsNotAnUpdate() {
        // A dev/snapshot build must never be "updated" down to an older release.
        assertFalse(PluginUpdaterUtils.isNewerThan("2.0.0", "1.9.0"));
        assertFalse(PluginUpdaterUtils.isNewerThan("2.0.0-SNAPSHOT", "1.9.0"));
        assertFalse(PluginUpdaterUtils.versionsMatch("2.0.0", "1.9.0"));
    }

    @Test
    void buildMetadataCompares() {
        assertTrue(PluginUpdaterUtils.isNewerThan("1.0", "1.0+5"));
        assertFalse(PluginUpdaterUtils.isNewerThan("1.0+5", "1.0+3"));
        assertTrue(PluginUpdaterUtils.isNewerThan("1.0+3", "1.0+5"));
    }

    @Test
    void joinArgs() {
        assertEquals("a b c", PluginUpdaterUtils.joinArgs(new String[]{"x", "a", "b", "c"}, 1));
        assertEquals("a b", PluginUpdaterUtils.joinArgs(new String[]{"x", "a", "b", "c"}, 1, 3));
        assertEquals("", PluginUpdaterUtils.joinArgs(new String[]{"x"}, 1));
    }
}
