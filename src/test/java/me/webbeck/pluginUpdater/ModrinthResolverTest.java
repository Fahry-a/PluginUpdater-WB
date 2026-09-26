package me.webbeck.pluginUpdater;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModrinthResolverTest {

    @Test
    void tokenizeSplitsHyphensAndCamelCase() {
        assertEquals(
                List.of("all", "in", "one", "vein", "miner", "tree", "feller"),
                ModrinthResolver.tokenize("All-In-One-VeinMiner-TreeFeller"));
        assertEquals(List.of("sit"), ModrinthResolver.tokenize("GSit"));
        assertEquals(List.of("via", "version"), ModrinthResolver.tokenize("ViaVersion"));
    }

    @Test
    void truncatedTitleStillWins() {
        // Real case: Modrinth title "All-in-One VeinMiner & TreeFelle" vs local
        // "All-In-One-VeinMiner-TreeFeller". Exact matching failed here.
        List<String> tokens = ModrinthResolver.tokenize("All-In-One-VeinMiner-TreeFeller");
        String normQuery = "allinoneveinminertreefeller";
        int target = ModrinthResolver.scoreHit(tokens, normQuery,
                "All-in-One VeinMiner & TreeFelle", "all-in-one-veinminer", "mod");
        int competitor = ModrinthResolver.scoreHit(tokens, normQuery,
                "VeinMiner", "veinminer", "mod");
        assertTrue(target > competitor, "target=" + target + " competitor=" + competitor);
        assertTrue(target >= ModrinthResolver.SCORE_THRESHOLD);
    }

    @Test
    void exactTitleScoresExact() {
        List<String> tokens = ModrinthResolver.tokenize("GSit");
        assertEquals(ModrinthResolver.SCORE_EXACT,
                ModrinthResolver.scoreHit(tokens, "gsit", "GSit", "gsit", "plugin"));
    }

    @Test
    void pickBestRejectsCloseCall() {
        ModrinthResolver.Candidate a = new ModrinthResolver.Candidate("a", "Foo Bar", "foo-bar", "plugin", 8);
        ModrinthResolver.Candidate b = new ModrinthResolver.Candidate("b", "Foo Baz", "foo-baz", "plugin", 7);
        ModrinthResolver.Resolution resolution = ModrinthResolver.pickBest(List.of(a, b));
        assertFalse(resolution.found);
        assertEquals(2, resolution.candidates.size());
    }

    @Test
    void pickBestAcceptsClearWinner() {
        ModrinthResolver.Candidate a = new ModrinthResolver.Candidate("a", "Foo", "foo", "plugin", 22);
        ModrinthResolver.Candidate b = new ModrinthResolver.Candidate("b", "Fooish", "fooish", "mod", 9);
        ModrinthResolver.Resolution resolution = ModrinthResolver.pickBest(List.of(a, b));
        assertTrue(resolution.found);
        assertEquals("a", resolution.id);
    }

    @Test
    void pickBestRejectsLowScores() {
        ModrinthResolver.Candidate a = new ModrinthResolver.Candidate("a", "Unrelated", "unrelated", "mod", 3);
        assertFalse(ModrinthResolver.pickBest(List.of(a)).found);
    }
}
