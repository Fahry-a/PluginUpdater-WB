package me.webbeck.pluginUpdater;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpigotSuggestTest {

    @Test
    void closeMatch() {
        assertTrue(ConfigManager.spigotCloseMatch("veinminer", "veinminerultimate"));
        assertTrue(ConfigManager.spigotCloseMatch("allinoneveinminer", "veinminer"));
        assertFalse(ConfigManager.spigotCloseMatch("veinminer", "veinminer"));
        assertFalse(ConfigManager.spigotCloseMatch("gsit", "essentialsx"));
        assertFalse(ConfigManager.spigotCloseMatch("", "anything"));
    }

    @Test
    void describeCandidates() {
        assertEquals("", ConfigManager.describeSpigotCandidates(List.of()));
        String text = ConfigManager.describeSpigotCandidates(
                List.of(new ConfigManager.SpigotCandidate("12038", "VeinMiner")));
        assertTrue(text.contains("VeinMiner") && text.contains("12038"));
    }

    @Test
    void privateJarHintMentionsBothEscapes() {
        String hint = ConfigManager.privateJarHint("Foo");
        assertTrue(hint.contains("/upd plugin id Foo"));
        assertTrue(hint.contains("Custom"));
        assertTrue(hint.contains("/upd plugin toggle Foo false"));
    }
}
