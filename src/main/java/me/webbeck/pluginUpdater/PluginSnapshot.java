package me.webbeck.pluginUpdater;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable per-plugin state captured on the main thread before any async work.
 * Workers must use this instead of touching Bukkit or YamlConfiguration off-thread.
 */
public final class PluginSnapshot {
    public final String pluginName;
    public final String type;
    public final String projectId;
    public final String githubRepo;
    public final String customUrl;
    public final List<String> allowedTypes;
    public final String currentVersion;
    public final String serverType;
    public final String minecraftVersion;
    public final File runningJar;
    public final String runningVersion;
    public final String expectedSha1;
    public final String expectedSha256;
    public final String lastEtag;
    public final String lastModified;
    /** False skips the Modrinth game_versions filter (saves a request when no build matches). */
    public final boolean gameVersionFilter;
    /** Exact GitHub release asset filename to prefer; null = fuzzy match. */
    public final String githubAsset;

    public PluginSnapshot(String pluginName, String type, String projectId, String githubRepo,
                          String customUrl, List<String> allowedTypes, String currentVersion,
                          String serverType, String minecraftVersion, File runningJar,
                          String runningVersion, String expectedSha1, String expectedSha256,
                          String lastEtag, String lastModified) {
        this(pluginName, type, projectId, githubRepo, customUrl, allowedTypes, currentVersion,
                serverType, minecraftVersion, runningJar, runningVersion,
                expectedSha1, expectedSha256, lastEtag, lastModified, true, null);
    }

    public PluginSnapshot(String pluginName, String type, String projectId, String githubRepo,
                          String customUrl, List<String> allowedTypes, String currentVersion,
                          String serverType, String minecraftVersion, File runningJar,
                          String runningVersion, String expectedSha1, String expectedSha256,
                          String lastEtag, String lastModified,
                          boolean gameVersionFilter, String githubAsset) {
        this.pluginName = pluginName;
        this.type = type != null ? type.toUpperCase() : "MODRINTH";
        this.projectId = projectId;
        this.githubRepo = githubRepo;
        this.customUrl = customUrl;
        this.allowedTypes = allowedTypes != null
                ? Collections.unmodifiableList(new ArrayList<>(allowedTypes))
                : List.of("release");
        this.currentVersion = currentVersion != null ? currentVersion : "0.0.0";
        this.serverType = serverType;
        this.minecraftVersion = minecraftVersion;
        this.runningJar = runningJar;
        this.runningVersion = runningVersion;
        this.expectedSha1 = expectedSha1;
        this.expectedSha256 = expectedSha256;
        this.lastEtag = lastEtag;
        this.lastModified = lastModified;
        this.gameVersionFilter = gameVersionFilter;
        this.githubAsset = githubAsset;
    }
}
