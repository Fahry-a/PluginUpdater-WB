package me.webbeck.pluginUpdater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class UpdateChecker {
    private final PluginUpdater plugin;
    private final ConfigManager configManager;
    private final HttpClient httpClient;
    private final AtomicBoolean checkRunningNormal = new AtomicBoolean(false);
    private final AtomicBoolean checkRunningVersions = new AtomicBoolean(false);
    private volatile SourceRegistry registry;

    public UpdateChecker(PluginUpdater plugin, ConfigManager configManager, HttpClient httpClient) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.httpClient = httpClient;
    }

    public void setRegistry(SourceRegistry registry) {
        this.registry = registry;
    }

    /**
     * Snapshot everything the workers need while still on the calling (usually main) thread.
     * No Bukkit or Yaml access happens off-thread after this returns.
     */
    List<PluginSnapshot> snapshotPlugins(boolean bypassFilters) {
        ConfigurationSection pluginsSec = plugin.getPluginsConfig();
        List<PluginSnapshot> out = new ArrayList<>();
        if (pluginsSec == null) return out;
        List<String> keys = new ArrayList<>(pluginsSec.getKeys(false));
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        String mcVersion = configManager.getMinecraftVersion();
        for (String pluginName : keys) {
            ConfigurationSection pSec = pluginsSec.getConfigurationSection(pluginName);
            if (pSec == null || !pSec.getBoolean("enabled", true)) continue;
            if (!pSec.getBoolean("installed", true)) continue;
            String type = pSec.getString("type", "MODRINTH").toUpperCase();
            List<String> allowedTypes = new ArrayList<>(pSec.getStringList("allowed-release-types"));
            if (bypassFilters || allowedTypes.contains("all") || allowedTypes.contains("ALL")) {
                allowedTypes = Arrays.asList("release", "beta", "alpha", "prerelease");
            }
            Plugin runningPlugin = Bukkit.getPluginManager().getPlugin(pluginName);
            String currentVer = runningPlugin != null
                    ? runningPlugin.getDescription().getVersion()
                    : pSec.getString("current-version", "0.0.0");
            java.io.File runningJar = null;
            String runningVersion = currentVer;
            if (runningPlugin != null) {
                runningVersion = runningPlugin.getDescription().getVersion();
                try {
                    var codeSource = runningPlugin.getClass().getProtectionDomain().getCodeSource();
                    if (codeSource != null && codeSource.getLocation() != null) {
                        java.io.File jar = new java.io.File(codeSource.getLocation().toURI());
                        if (jar.isFile()) runningJar = jar;
                    }
                } catch (Exception ignored) {
                }
            }
            String serverType = configManager.getPluginServerType(pluginName);
            out.add(new PluginSnapshot(pluginName, type,
                    pSec.getString("project-id"), pSec.getString("github-repo"), pSec.getString("custom-url"),
                    allowedTypes, currentVer, serverType, mcVersion, runningJar, runningVersion,
                    pSec.getString("expected-sha1"), pSec.getString("expected-sha256"),
                    pSec.getString("last-etag"), pSec.getString("last-modified"),
                    pSec.getBoolean("game-version-filter", true), pSec.getString("github-asset", null)));
        }
        return out;
    }

    public void runUpdateCheck(CommandSender sender, boolean bypassFilters, String listMode) {
        AtomicBoolean lock = bypassFilters ? checkRunningVersions : checkRunningNormal;
        if (!lock.compareAndSet(false, true)) {
            plugin.sendMsg(sender, ChatColor.YELLOW + "An update check is already running - please wait.");
            return;
        }
        plugin.sendMsg(sender, ChatColor.AQUA + "Starting async plugin update check...");

        // Capture Bukkit/Yaml state on this thread; workers only see snapshots.
        List<PluginSnapshot> snapshots;
        try {
            snapshots = snapshotPlugins(bypassFilters);
        } catch (Exception e) {
            lock.set(false);
            plugin.sendMsg(sender, ChatColor.RED + "Failed to snapshot plugin list: " + e.getMessage());
            return;
        }
        String serverVersionLower;
        try {
            serverVersionLower = Bukkit.getVersion().toLowerCase();
        } catch (Exception e) {
            serverVersionLower = "";
        }
        final String serverVersionForLoaders = serverVersionLower;

        plugin.getIoExecutors().checkPool().execute(() -> {
            try {
            Map<String, UpdateInfo> results = bypassFilters ? plugin.getUnfilteredUpdates() : plugin.getPendingUpdates();
            results.clear();
            plugin.getCheckErrors().clear();

            AtomicInteger current = new AtomicInteger(0);
            int total = snapshots.size();
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            java.util.concurrent.ExecutorService pool = plugin.getIoExecutors().checkPool();

            for (PluginSnapshot snap : snapshots) {
                final PluginSnapshot s = snap;
                futures.add(pool.submit(() -> {
                    int idx = current.incrementAndGet();
                    plugin.updateActionBar(sender, "Checking Plugins: " + idx + "/" + total + " (" + s.pluginName + ")");
                    try {
                        UpdateInfo foundUpdate = (registry != null ? registry.check(s) : checkSnapshot(s, serverVersionForLoaders));

                        if (foundUpdate != null && shouldReportUpdate(s, foundUpdate)) {
                            if (installedJarMatchesHash(s.runningJar, foundUpdate)) {
                                plugin.getLogger().info(s.pluginName + " reports " + s.currentVersion
                                        + " but the installed jar matches remote build " + foundUpdate.newVersion + " - up to date.");
                            } else {
                                results.put(s.pluginName.toLowerCase(), foundUpdate);
                            }
                        }
                    } catch (SourceException e) {
                        plugin.getCheckErrors().put(s.pluginName.toLowerCase(),
                                new CheckError(e.getKind(), s.pluginName, e.getMessage()));
                    } catch (Exception e) {
                        String detail = e.getMessage() != null ? e.getMessage() : e.toString();
                        plugin.getCheckErrors().put(s.pluginName.toLowerCase(),
                                new CheckError(SourceException.Kind.SERVER_ERROR, s.pluginName, detail));
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                }
            }

            plugin.updateActionBar(sender, "");
            plugin.setInitialCheckComplete();

            final String mode = listMode;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (mode != null) {
                    displayPluginList(sender, mode);
                } else {
                    plugin.sendMsg(sender, ChatColor.GREEN + "Update check complete! Found " + results.size() + " pending updates.");
                    if (!results.isEmpty()) {
                        plugin.sendMsg(sender, ChatColor.YELLOW + "Use /upd list to view them.");
                    }
                    if (!plugin.getCheckErrors().isEmpty()) {
                        int failed = plugin.getCheckErrors().size();
                        plugin.sendMsg(sender, ChatColor.RED + "" + failed
                                + " plugin(s) could not be checked. Use /upd errors for details.");
                        for (CheckError error : plugin.getCheckErrors().values()) {
                            plugin.getLogger().warning("Check failed for " + error.pluginName + ": "
                                    + error.label() + " - " + error.detail);
                        }
                    }
                }
            });
            } finally {
                lock.set(false);
            }
        });
    }

    private UpdateInfo checkSnapshot(PluginSnapshot snap, String serverVersionLower) throws Exception {
        String type = snap.type.toUpperCase();
        if (type.equals("MODRINTH")) {
            return checkModrinth(snap.pluginName, snap.projectId, snap.currentVersion, snap.allowedTypes, snap.serverType, serverVersionLower, snap.gameVersionFilter);
        } else if (type.equals("GITHUB")) {
            return checkGitHub(snap.pluginName, snap.githubRepo, snap.currentVersion, snap.allowedTypes, snap.githubAsset);
        } else if (type.equals("HANGAR")) {
            return checkHangar(snap.pluginName, snap.projectId, snap.currentVersion, snap.allowedTypes, snap.serverType);
        } else if (type.equals("SPIGOT")) {
            return checkSpigot(snap.pluginName, snap.projectId, snap.currentVersion, snap.lastEtag, snap.lastModified);
        } else if (type.equals("CUSTOM")) {
            return checkCustom(snap);
        }
        throw new SourceException(SourceException.Kind.NO_SOURCE, snap.pluginName, "unknown source type '" + type + "'");
    }

    /** Central version/hash/ETag gate shared by normal and bypass listings. */
    boolean shouldReportUpdate(PluginSnapshot snap, UpdateInfo found) {
        String type = snap.type.toUpperCase();
        if (type.equals("CUSTOM")) {
            // Custom has no version comparison: ETag unchanged means no change.
            if (found.remoteEtag != null && found.remoteEtag.equals(snap.lastEtag) && snap.lastEtag != null) {
                return false;
            }
            if (found.remoteEtag == null && found.remoteLastModified != null
                    && found.remoteLastModified.equals(snap.lastModified) && snap.lastModified != null) {
                return false;
            }
            return true;
        }
        if (type.equals("SPIGOT") && !PluginUpdaterUtils.isNewerThan(snap.currentVersion, found.newVersion)) {
            // Spiget version strings are free-form; a changed ETag still signals a new file.
            if (found.remoteEtag != null && snap.lastEtag != null && !found.remoteEtag.equals(snap.lastEtag)) {
                return true;
            }
            return false;
        }
        return PluginUpdaterUtils.isNewerThan(snap.currentVersion, found.newVersion);
    }

    public void displayPluginList(CommandSender sender, String listMode) {
        String header;
        switch (listMode == null ? "pending" : listMode.toLowerCase()) {
            case "all":
                header = "=== All Enabled Plugins ===";
                break;
            case "versions":
                header = "=== Plugin Versions ===";
                break;
            default:
                header = "=== Pending Updates ===";
                break;
        }

        plugin.sendMsg(sender, ChatColor.GOLD + header);
        ConfigurationSection pSec = plugin.getPluginsConfig();
        Map<String, UpdateInfo> results = listMode != null && listMode.equalsIgnoreCase("versions")
                ? plugin.getUnfilteredUpdates()
                : plugin.getPendingUpdates();
        if (pSec != null) {
            boolean foundAny = false;
            List<String> sortedKeys = new ArrayList<>(pSec.getKeys(false));
            sortedKeys.sort(String.CASE_INSENSITIVE_ORDER);

            for (String pName : sortedKeys) {
                if (!pSec.getBoolean(pName + ".enabled", true)) continue;
                if (!pSec.getBoolean(pName + ".installed", true)) continue;

                String serverType = configManager.getPluginServerType(pName);
                String sourceType = configManager.getPluginSourceType(pName);
                Plugin p = Bukkit.getPluginManager().getPlugin(pName);
                String currentVersion = p != null ? p.getDescription().getVersion() : pSec.getString(pName + ".current-version", "Unknown");
                UpdateInfo info = results.get(pName.toLowerCase());
                CheckError checkError = plugin.getCheckErrors().get(pName.toLowerCase());
                if (checkError != null) {
                    plugin.sendMsg(sender, ChatColor.RED + pName
                            + ChatColor.DARK_GRAY + " [" + sourceType + "]"
                            + ChatColor.GRAY + " [CUR " + currentVersion + "]"
                            + ChatColor.RED + " [" + checkError.label() + "]");
                    foundAny = true;
                    continue;
                }

                if (listMode.equalsIgnoreCase("all") || listMode.equalsIgnoreCase("versions")) {
                    StringBuilder line = new StringBuilder();
                    line.append(ChatColor.AQUA).append(pName);
                    line.append(ChatColor.DARK_GRAY).append(" [").append(sourceType).append("]");
                    line.append(ChatColor.YELLOW).append(" [").append(serverType.toUpperCase()).append("]");
                    line.append(ChatColor.GRAY).append(" [CUR ").append(currentVersion).append("]");

                    if (info != null) {
                        line.append(ChatColor.GRAY).append(" -> ");
                        line.append(ChatColor.AQUA).append("[").append(info.newVersion).append("]");
                        line.append(ChatColor.RED).append(" [UPDATE AVAILABLE]");
                    } else {
                        line.append(ChatColor.GREEN).append(" [UP TO DATE]");
                    }

                    if (listMode.equalsIgnoreCase("versions") && info == null) {
                        line = new StringBuilder();
                        line.append(ChatColor.AQUA).append(pName);
                        line.append(ChatColor.DARK_GRAY).append(" [").append(sourceType).append("]");
                        line.append(ChatColor.YELLOW).append(" [").append(serverType.toUpperCase()).append("]");
                        line.append(ChatColor.GRAY).append(" [CUR ").append(currentVersion).append("]");
                        line.append(ChatColor.GREEN).append(" [UP TO DATE]");
                    }

                    plugin.sendMsg(sender, line.toString());
                    foundAny = true;
                } else {
                    if (info != null) {
                        plugin.sendInteractiveListMsg(sender, pName, info.oldVersion, info.newVersion, true);
                        foundAny = true;
                    }
                }
            }
            if (!foundAny && listMode.equalsIgnoreCase("pending")) {
                plugin.sendMsg(sender, ChatColor.GREEN + "All tracked plugins are currently up to date!");
            }
        }
    }

    public String getRealSpigotId(String pluginName) throws Exception {
        return configManager.getRealSpigotId(pluginName);
    }

    /** Registry dispatch for callers that already hold a main-thread snapshot. */
    public UpdateInfo getRegistrySnapshotCheck(PluginSnapshot snapshot) throws Exception {
        if (registry != null) return registry.check(snapshot);
        return checkSnapshot(snapshot, null);
    }

    public Map<String, String> getRegistrySnapshotChannels(PluginSnapshot snapshot) throws Exception {
        if (registry != null) return registry.fetchAllChannels(snapshot);
        return Map.of();
    }

    private boolean installedJarMatchesHash(java.io.File runningJar, UpdateInfo info) {
        if (runningJar == null) return false;
        boolean wantSha1 = info.expectedSha1 != null && !info.expectedSha1.isBlank();
        boolean wantSha256 = info.expectedSha256 != null && !info.expectedSha256.isBlank();
        if (!wantSha1 && !wantSha256) return false;
        try {
            java.nio.file.Path path = runningJar.toPath();
            if (wantSha1) return JarHasher.matchesSha1(path, info.expectedSha1);
            return JarHasher.matchesSha256(path, info.expectedSha256);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean installedJarMatchesHash(Plugin runningPlugin, UpdateInfo info) {
        if (runningPlugin == null) return false;
        try {
            var codeSource = runningPlugin.getClass().getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) return false;
            java.io.File jar = new java.io.File(codeSource.getLocation().toURI());
            return installedJarMatchesHash(jar, info);
        } catch (Exception e) {
            return false;
        }
    }

    private String buildModrinthLoaders(String serverType) {
        return buildModrinthLoaders(serverType, null);
    }

    private String buildModrinthLoaders(String serverType, String serverVersionLower) {
        if (serverType.equalsIgnoreCase("auto")) {
            String v = serverVersionLower;
            if (v == null) {
                try {
                    v = Bukkit.getVersion().toLowerCase();
                } catch (Exception e) {
                    v = "";
                }
            }
            return v.contains("paper") ? "[\"paper\",\"spigot\",\"bukkit\"]" : "[\"spigot\",\"bukkit\"]";
        }
        return "[\"" + serverType.toLowerCase() + "\"]";
    }

    private JsonArray requestModrinthVersions(String pluginName, String projectId, String loadersStr, String gameVersion) throws Exception {
        StringBuilder url = new StringBuilder("https://api.modrinth.com/v2/project/").append(projectId).append("/version?loaders=")
                .append(URLEncoder.encode(loadersStr, StandardCharsets.UTF_8.toString()));
        if (gameVersion != null && !gameVersion.isBlank()) {
            String gameVerStr = "[\"" + gameVersion + "\"]";
            url.append("&game_versions=").append(URLEncoder.encode(gameVerStr, StandardCharsets.UTF_8.toString()));
        }

        HttpRequest request = HttpRequest.newBuilder().timeout(java.time.Duration.ofSeconds(15)).uri(URI.create(url.toString())).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int status = response.statusCode();
        if (status == 404) {
            throw new SourceException(SourceException.Kind.NOT_FOUND, pluginName,
                    "Modrinth project '" + projectId + "' not found (HTTP 404)");
        }
        if (status == 403 || status == 429) {
            throw new SourceException(SourceException.Kind.RATE_LIMITED, pluginName,
                    "Modrinth refused the request (HTTP " + status + ")");
        }
        if (status < 200 || status > 299) {
            throw new SourceException(SourceException.Kind.SERVER_ERROR, pluginName,
                    "Modrinth request failed (HTTP " + status + ")");
        }
        return JsonParser.parseString(response.body()).getAsJsonArray();
    }

    UpdateInfo checkModrinth(String pluginName, String projectId, String currentVer, List<String> allowedTypes, String serverType) throws Exception {
        return checkModrinth(pluginName, projectId, currentVer, allowedTypes, serverType, null);
    }

    UpdateInfo checkModrinth(String pluginName, String projectId, String currentVer, List<String> allowedTypes, String serverType, String serverVersionLower) throws Exception {
        return checkModrinth(pluginName, projectId, currentVer, allowedTypes, serverType, serverVersionLower, true);
    }

    UpdateInfo checkModrinth(String pluginName, String projectId, String currentVer, List<String> allowedTypes, String serverType, String serverVersionLower, boolean useGameVersionFilter) throws Exception {
        if (projectId == null || projectId.isBlank()) {
            throw new SourceException(SourceException.Kind.NO_SOURCE, pluginName, "no project-id configured");
        }
        String loadersStr = buildModrinthLoaders(serverType, serverVersionLower);
        String gameVersion = useGameVersionFilter ? configManager.getMinecraftVersion() : null;

        JsonArray versions = requestModrinthVersions(pluginName, projectId, loadersStr, gameVersion);
        if (versions.size() == 0 && gameVersion != null && !gameVersion.isBlank()) {
            JsonArray fallback = requestModrinthVersions(pluginName, projectId, loadersStr, null);
            if (fallback.size() > 0) {
                plugin.getLogger().info(pluginName + " [Modrinth: " + projectId + "] has no build for Minecraft " + gameVersion
                        + " - falling back to the latest matching build.");
                versions = fallback;
            }
        }
        for (JsonElement element : versions) {
            JsonObject vObj = element.getAsJsonObject();
            String vType = vObj.get("version_type").getAsString();

            if (!allowedTypes.contains(vType)) continue;

            String newVer = vObj.get("version_number").getAsString();
            JsonArray files = vObj.getAsJsonArray("files");
            if (files.size() == 0) continue;

            JsonObject primaryFile = selectModrinthFile(files, pluginName);
            if (primaryFile == null) {
                plugin.getLogger().warning("Modrinth version " + newVer + " of " + pluginName
                        + " has multiple files with no clear match (" + modrinthFileNames(files) + ") - skipping.");
                continue;
            }
            String downloadUrl = primaryFile.get("url").getAsString();
            String fileName = primaryFile.get("filename").getAsString();

            UpdateInfo info = new UpdateInfo(pluginName, currentVer, newVer, downloadUrl, fileName);
            if (primaryFile.has("hashes") && primaryFile.get("hashes").isJsonObject()) {
                JsonObject hashes = primaryFile.getAsJsonObject("hashes");
                if (hashes.has("sha1") && !hashes.get("sha1").isJsonNull()) {
                    info.expectedSha1 = hashes.get("sha1").getAsString();
                }
            }
            JsonArray deps = vObj.getAsJsonArray("dependencies");
            if (deps != null) {
                for (JsonElement depElem : deps) {
                    JsonObject depObj = depElem.getAsJsonObject();
                    if (depObj.has("dependency_type") && depObj.get("dependency_type").getAsString().equals("required")) {
                        JsonElement projIdElem = depObj.get("project_id");
                        if (!projIdElem.isJsonNull()) {
                            info.requiredDependencies.add(projIdElem.getAsString());
                        }
                    }
                }
            }
            return info;
        }
        return null;
    }

    private JsonObject selectModrinthFile(JsonArray files, String pluginName) {
        if (files.size() == 1) return files.get(0).getAsJsonObject();
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            if (file.has("primary") && file.get("primary").getAsBoolean()) return file;
        }
        String wanted = pluginName.toLowerCase().replaceAll("[^a-z0-9]", "");
        java.util.List<JsonObject> matches = new java.util.ArrayList<>();
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            String fileName = file.has("filename")
                    ? file.get("filename").getAsString().toLowerCase().replaceAll("[^a-z0-9]", "")
                    : "";
            if (!wanted.isEmpty() && fileName.contains(wanted)) matches.add(file);
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private String modrinthFileNames(JsonArray files) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (JsonElement element : files) {
            try {
                names.add(element.getAsJsonObject().get("filename").getAsString());
            } catch (Exception ignored) {
            }
        }
        return String.join(", ", names);
    }

    private final java.util.Map<String, String> gitHubEtags = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, String> gitHubCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * GET against the GitHub API with optional token auth and ETag caching.
     * Returns the body, or null when there is nothing usable. A 403 (usually the
     * unauthenticated rate limit) is logged with a pointer at github-token.
     */
    private String getGitHubBody(String url, String context) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder().timeout(java.time.Duration.ofSeconds(15)).uri(URI.create(url))
                .header("User-Agent", "PluginUpdater-WB")
                .header("Accept", "application/vnd.github+json");
        String token = configManager.getGitHubToken();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token.trim());
        }
        String etag = gitHubEtags.get(url);
        if (etag != null) {
            builder.header("If-None-Match", etag);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status == 304) {
            String cached = gitHubCache.get(url);
            if (cached == null) {
                throw new SourceException(SourceException.Kind.SERVER_ERROR, context,
                        "GitHub cache expired without a stored response");
            }
            return cached;
        }
        if (status == 403) {
            throw new SourceException(SourceException.Kind.RATE_LIMITED, context,
                    "GitHub API denied access (HTTP 403 - rate limit?). Set a github-token in config.yml to raise the limit.");
        }
        if (status < 200 || status > 299) {
            throw new SourceException(SourceException.Kind.SERVER_ERROR, context,
                    "GitHub API request failed (HTTP " + status + ")");
        }
        response.headers().firstValue("ETag").ifPresent(value -> {
            gitHubEtags.put(url, value);
            gitHubCache.put(url, response.body());
        });
        return response.body();
    }

    /**
     * Pure asset selection, unit-testable. An explicit pin always wins: exact
     * case-insensitive filename match, no fuzzy fallback (a pinned name that is
     * absent means "not this release", so the caller keeps scanning older ones).
     */
    static JsonObject selectPinnedAsset(java.util.List<JsonObject> candidates, String preferredAsset) {
        if (preferredAsset == null || preferredAsset.isBlank()) return null;
        String wanted = preferredAsset.trim();
        for (JsonObject asset : candidates) {
            try {
                if (wanted.equalsIgnoreCase(asset.get("name").getAsString())) return asset;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private JsonObject chooseGitHubAsset(java.util.List<JsonObject> candidates, String pluginName, String releaseName) {
        return chooseGitHubAsset(candidates, pluginName, releaseName, null);
    }

    private JsonObject chooseGitHubAsset(java.util.List<JsonObject> candidates, String pluginName, String releaseName, String preferredAsset) {
        if (candidates.isEmpty()) return null;
        if (preferredAsset != null && !preferredAsset.isBlank()) {
            return selectPinnedAsset(candidates, preferredAsset);
        }
        if (candidates.size() == 1) return candidates.get(0);
        String wanted = pluginName.toLowerCase().replaceAll("[^a-z0-9]", "");
        java.util.List<JsonObject> matches = new java.util.ArrayList<>();
        for (JsonObject asset : candidates) {
            String fileName = asset.get("name").getAsString().toLowerCase().replaceAll("[^a-z0-9]", "");
            if (!wanted.isEmpty() && fileName.contains(wanted)) matches.add(asset);
        }
        if (matches.size() == 1) return matches.get(0);
        java.util.List<String> names = new java.util.ArrayList<>();
        for (JsonObject asset : candidates) names.add(asset.get("name").getAsString());
        plugin.getLogger().warning("GitHub release " + releaseName + " of " + pluginName
                + " has multiple jars with no clear match (" + String.join(", ", names) + ") - skipping.");
        return null;
    }

    /**
     * Checks only PluginUpdater-WB's official GitHub Releases for a self-update.
     * This is intentionally separate from the normal plugin scan so /upd self and
     * the periodic self-check do not depend on other plugin sources.
     */
    public void checkSelfUpdate(CommandSender sender, boolean autoDownload) {
        if (!plugin.getConfig().getBoolean("self-update.enabled", true)) {
            plugin.sendMsg(sender, ChatColor.YELLOW + "PluginUpdater-WB self-update is disabled.");
            return;
        }

        plugin.sendMsg(sender, ChatColor.AQUA + "Checking PluginUpdater-WB GitHub Releases...");
        plugin.getIoExecutors().checkPool().execute(() -> {
            try {
                String current = plugin.getDescription().getVersion();
                String currentReleaseVersion = extractSelfReleaseVersion(current);
                UpdateInfo info = checkGitHub("PluginUpdater-WB", "Fahry-a/PluginUpdater-WB", current,
                        Collections.singletonList("release"), null);

                if (info == null) {
                    plugin.sendMsg(sender, ChatColor.GREEN + "PluginUpdater-WB is up to date (" + currentReleaseVersion + ").");
                    return;
                }

                String remoteReleaseVersion = extractSelfReleaseVersion(info.newVersion);
                if (currentReleaseVersion == null || remoteReleaseVersion == null) {
                    plugin.sendMsg(sender, ChatColor.YELLOW + "Could not compare self-update versions: "
                            + current + " -> " + info.newVersion);
                    return;
                }

                if (!PluginUpdaterUtils.isNewerThan(currentReleaseVersion, remoteReleaseVersion)) {
                    plugin.sendMsg(sender, ChatColor.GREEN + "PluginUpdater-WB is up to date (" + currentReleaseVersion + ").");
                    return;
                }

                plugin.sendMsg(sender, ChatColor.YELLOW + "PluginUpdater-WB update available: "
                        + currentReleaseVersion + " -> " + remoteReleaseVersion);
                plugin.getPendingUpdates().put(info.pluginName.toLowerCase(Locale.ROOT), info);

                if (autoDownload && plugin.getConfig().getBoolean("self-update.auto-download", true)) {
                    plugin.sendMsg(sender, ChatColor.AQUA + "Staging the self-update in Paper's update folder...");
                    Bukkit.getScheduler().runTask(plugin, () ->
                            plugin.getUpdateDownloader().applyUpdates(sender, Collections.singletonList(info)));
                } else {
                    plugin.sendMsg(sender, ChatColor.YELLOW + "Use /upd self update to stage it for the next restart.");
                }
            } catch (SourceException e) {
                plugin.sendMsg(sender, ChatColor.RED + "Self-update check failed: " + e.getMessage());
            } catch (Exception e) {
                plugin.sendMsg(sender, ChatColor.RED + "Self-update check failed: "
                        + (e.getMessage() != null ? e.getMessage() : e));
            }
        });
    }

    private static String extractSelfReleaseVersion(String version) {
        if (version == null) return null;
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:^|[-+])(\\d+\\.\\d+\\.\\d+)(?:[-+].*)?$")
                .matcher(normalized);
        return matcher.find() ? matcher.group(1) : null;
    }

    UpdateInfo checkGitHub(String pluginName, String repo, String currentVer, List<String> allowedTypes) throws Exception {
        return checkGitHub(pluginName, repo, currentVer, allowedTypes, null);
    }

    UpdateInfo checkGitHub(String pluginName, String repo, String currentVer, List<String> allowedTypes, String preferredAsset) throws Exception {
        if (repo == null || repo.isBlank()) {
            throw new SourceException(SourceException.Kind.NO_SOURCE, pluginName, "no github-repo configured");
        }
        String url = "https://api.github.com/repos/" + repo + "/releases";
        String body = getGitHubBody(url, repo);

        JsonArray releases = JsonParser.parseString(body).getAsJsonArray();
        for (JsonElement element : releases) {
            JsonObject rObj = element.getAsJsonObject();
            boolean isPrerelease = rObj.get("prerelease").getAsBoolean();
            String effectiveType = isPrerelease ? "beta" : "release";

            if (!allowedTypes.contains(effectiveType)) continue;

            String newVer = rObj.get("tag_name").getAsString();
            JsonArray assets = rObj.getAsJsonArray("assets");

            java.util.List<JsonObject> jarAssets = new java.util.ArrayList<>();
            for (JsonElement assetElem : assets) {
                JsonObject assetObj = assetElem.getAsJsonObject();
                String fileName = assetObj.get("name").getAsString();
                if (fileName.toLowerCase().endsWith(".jar")
                        && !fileName.toLowerCase().matches(".*-(sources|javadoc)\\.jar$")) {
                    jarAssets.add(assetObj);
                }
            }
            JsonObject chosen = chooseGitHubAsset(jarAssets, pluginName, newVer, preferredAsset);
            if (chosen != null) {
                String fileName = chosen.get("name").getAsString();
                String downloadUrl = chosen.get("browser_download_url").getAsString();
                UpdateInfo info = new UpdateInfo(pluginName, currentVer, newVer, downloadUrl, fileName);
                if (chosen.has("digest") && !chosen.get("digest").isJsonNull()) {
                    String digest = chosen.get("digest").getAsString();
                    int colon = digest.indexOf(':');
                    if (digest.toLowerCase().startsWith("sha256:") && colon > 0) {
                        info.expectedSha256 = digest.substring(colon + 1);
                    }
                }
                return info;
            }
        }
        return null;
    }

    UpdateInfo checkHangar(String pluginName, String projectId, String currentVer, List<String> allowedTypes, String serverType) throws Exception {
        if (projectId == null || projectId.isBlank()) {
            throw new SourceException(SourceException.Kind.NO_SOURCE, pluginName, "no project-id configured");
        }
        String url = "https://hangar.papermc.io/api/v1/projects/" + projectId + "/versions";
        HttpRequest request = HttpRequest.newBuilder().timeout(java.time.Duration.ofSeconds(15)).uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int status = response.statusCode();
        if (status == 404) {
            throw new SourceException(SourceException.Kind.NOT_FOUND, pluginName,
                    "Hangar project '" + projectId + "' not found (HTTP 404)");
        }
        if (status < 200 || status > 299) {
            throw new SourceException(SourceException.Kind.SERVER_ERROR, pluginName,
                    "Hangar request failed (HTTP " + status + ")");
        }

        JsonArray versions = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("result");
        String platform = serverType.equalsIgnoreCase("auto") ? "PAPER" : serverType.toUpperCase();

        for (JsonElement element : versions) {
            JsonObject vObj = element.getAsJsonObject();
            String channel = vObj.getAsJsonObject("channel").get("name").getAsString().toLowerCase();
            String effectiveType = channel.contains("snapshot") || channel.contains("beta") ? "beta" : "release";

            if (!allowedTypes.contains(effectiveType) && !allowedTypes.contains("all") && !allowedTypes.contains("ALL")) continue;

            String newVer = vObj.get("name").getAsString();
            JsonObject downloads = vObj.getAsJsonObject("downloads");

            JsonObject platformDownload = downloads.has(platform) ? downloads.getAsJsonObject(platform) : null;
            if (platformDownload == null && downloads.has("PAPER")) platformDownload = downloads.getAsJsonObject("PAPER");
            if (platformDownload == null && downloads.has("WATERFALL")) platformDownload = downloads.getAsJsonObject("WATERFALL");
            if (platformDownload == null && downloads.size() > 0) platformDownload = downloads.entrySet().iterator().next().getValue().getAsJsonObject();

            if (platformDownload != null && !platformDownload.isJsonNull()) {
                String finalPlatform = platform;
                if (!downloads.has(finalPlatform)) {
                    if (downloads.has("PAPER")) finalPlatform = "PAPER";
                    else finalPlatform = downloads.entrySet().iterator().next().getKey();
                }

                String downloadUrl = "https://hangar.papermc.io/api/v1/projects/" + projectId + "/versions/" + newVer + "/" + finalPlatform + "/download";
                String fileName = pluginName + "-" + newVer + ".jar";

                UpdateInfo info = new UpdateInfo(pluginName, currentVer, newVer, downloadUrl, fileName);
                if (platformDownload.has("fileInfo") && !platformDownload.get("fileInfo").isJsonNull()) {
                    JsonObject fileInfo = platformDownload.getAsJsonObject("fileInfo");
                    if (fileInfo.has("name") && !fileInfo.get("name").isJsonNull()) {
                        fileName = fileInfo.get("name").getAsString();
                        info = new UpdateInfo(pluginName, currentVer, newVer, downloadUrl, fileName);
                    }
                    if (fileInfo.has("sha256Hash") && !fileInfo.get("sha256Hash").isJsonNull()) {
                        info.expectedSha256 = fileInfo.get("sha256Hash").getAsString();
                    }
                }
                return info;
            }
        }
        return null;
    }

    UpdateInfo checkSpigot(String pluginName, String projectId, String currentVer) throws Exception {
        return checkSpigot(pluginName, projectId, currentVer, null, null);
    }

    UpdateInfo checkSpigot(String pluginName, String projectId, String currentVer, String lastEtag, String lastModified) throws Exception {
        if (projectId == null || projectId.isBlank()) {
            throw new SourceException(SourceException.Kind.NO_SOURCE, pluginName, "no project-id configured");
        }
        String url = "https://api.spiget.org/v2/resources/" + projectId + "/versions/latest";
        HttpRequest request = HttpRequest.newBuilder().timeout(java.time.Duration.ofSeconds(15)).uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int status = response.statusCode();
        if (status == 404) {
            throw new SourceException(SourceException.Kind.NOT_FOUND, pluginName,
                    "Spigot resource '" + projectId + "' not found (HTTP 404)");
        }
        if (status < 200 || status > 299) {
            throw new SourceException(SourceException.Kind.SERVER_ERROR, pluginName,
                    "Spiget request failed (HTTP " + status + ")");
        }

        JsonObject vObj = JsonParser.parseString(response.body()).getAsJsonObject();
        String newVer = vObj.get("name").getAsString();
        String downloadUrl = "https://api.spiget.org/v2/resources/" + projectId + "/download";
        String fileName = pluginName + "-" + newVer + ".jar";

        UpdateInfo info = new UpdateInfo(pluginName, currentVer, newVer, downloadUrl, fileName);
        // Spiget has no hash API; capture ETag/Last-Modified of the download so
        // free-form version strings don't cause perpetual false positives.
        String[] validators = probeRemoteValidators(downloadUrl);
        info.remoteEtag = validators[0];
        info.remoteLastModified = validators[1];
        return info;
    }

    /**
     * Custom URLs have no version API. Decision signals, in order:
     * 1. pinned expected-sha1/sha256 matching the installed jar means up to date,
     * 2. unchanged ETag/Last-Modified since the last successful download means no change,
     * 3. otherwise report an update (first run always updates).
     */
    UpdateInfo checkCustom(PluginSnapshot snap) throws Exception {
        String customUrl = snap.customUrl;
        if (customUrl == null || customUrl.isBlank()) {
            throw new SourceException(SourceException.Kind.NO_SOURCE, snap.pluginName, "no custom-url configured");
        }
        if (snap.runningJar != null) {
            if (snap.expectedSha1 != null && !snap.expectedSha1.isBlank()
                    && JarHasher.matchesSha1(snap.runningJar.toPath(), snap.expectedSha1)) {
                return null;
            }
            if (snap.expectedSha256 != null && !snap.expectedSha256.isBlank()
                    && JarHasher.matchesSha256(snap.runningJar.toPath(), snap.expectedSha256)) {
                return null;
            }
        }
        String[] validators = probeRemoteValidators(customUrl);
        String fileName = snap.pluginName + "-update.jar";
        try {
            String path = URI.create(customUrl).getPath();
            if (path != null && path.contains("/")) {
                String candidate = path.substring(path.lastIndexOf('/') + 1);
                if (candidate.toLowerCase().endsWith(".jar") && !candidate.isBlank()) fileName = candidate;
            }
        } catch (Exception ignored) {
        }
        UpdateInfo info = new UpdateInfo(snap.pluginName, snap.currentVersion, "Custom", customUrl, fileName);
        info.remoteEtag = validators[0];
        info.remoteLastModified = validators[1];
        if (snap.expectedSha1 != null && !snap.expectedSha1.isBlank()) info.expectedSha1 = snap.expectedSha1;
        if (snap.expectedSha256 != null && !snap.expectedSha256.isBlank()) info.expectedSha256 = snap.expectedSha256;
        return info;
    }

    /** HEAD probe for ETag / Last-Modified. Returns {etag, lastModified}, nulls when unavailable. */
    String[] probeRemoteValidators(String downloadUrl) {
        try {
            HttpRequest head = HttpRequest.newBuilder().uri(URI.create(downloadUrl))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", "PluginUpdater-WB").build();
            HttpResponse<Void> response = httpClient.send(head, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 400) {
                String etag = response.headers().firstValue("ETag").orElse(null);
                String lastMod = response.headers().firstValue("Last-Modified").orElse(null);
                if (etag != null || lastMod != null) return new String[]{etag, lastMod};
            }
        } catch (Exception ignored) {
        }
        return new String[]{null, null};
    }

    public Map<String, String> fetchAllChannelsModrinth(String projectId, String serverType) throws Exception {
        Map<String, String> latestVersions = new HashMap<>();
        String loadersStr = buildModrinthLoaders(serverType);
        String gameVersion = configManager.getMinecraftVersion();
        JsonArray versions = requestModrinthVersions(projectId, projectId, loadersStr, gameVersion);
        if (versions.size() == 0 && gameVersion != null && !gameVersion.isBlank()) {
            versions = requestModrinthVersions(projectId, projectId, loadersStr, null);
        }

        for (JsonElement element : versions) {
            JsonObject vObj = element.getAsJsonObject();
            String vType = vObj.get("version_type").getAsString();
            String newVer = vObj.get("version_number").getAsString();
            latestVersions.putIfAbsent(vType, newVer);
            if (latestVersions.size() >= 3) break;
        }
        return latestVersions;
    }

    public Map<String, String> fetchAllChannelsGitHub(String repo) throws Exception {
        Map<String, String> latestVersions = new HashMap<>();
        String url = "https://api.github.com/repos/" + repo + "/releases";

        String body = getGitHubBody(url, repo);
        if (body != null) {
            JsonArray releases = JsonParser.parseString(body).getAsJsonArray();
            for (JsonElement element : releases) {
                JsonObject rObj = element.getAsJsonObject();
                boolean isPrerelease = rObj.get("prerelease").getAsBoolean();
                String effectiveType = isPrerelease ? "beta" : "release";
                String newVer = rObj.get("tag_name").getAsString();
                latestVersions.putIfAbsent(effectiveType, newVer);
                if (latestVersions.containsKey("release") && latestVersions.containsKey("beta")) break;
            }
        }
        return latestVersions;
    }

    public Map<String, String> fetchAllChannelsHangar(String projectId) throws Exception {
        Map<String, String> latestVersions = new HashMap<>();
        String url = "https://hangar.papermc.io/api/v1/projects/" + projectId + "/versions";

        HttpRequest request = HttpRequest.newBuilder().timeout(java.time.Duration.ofSeconds(15)).uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonArray versions = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("result");
            for (JsonElement element : versions) {
                JsonObject vObj = element.getAsJsonObject();
                String channel = vObj.getAsJsonObject("channel").get("name").getAsString().toLowerCase();
                String effectiveType = channel.contains("snapshot") || channel.contains("beta") ? "beta" : "release";
                String newVer = vObj.get("name").getAsString();
                latestVersions.putIfAbsent(effectiveType, newVer);
                if (latestVersions.size() >= 2) break;
            }
        }
        return latestVersions;
    }

    public Map<String, String> fetchAllChannelsSpigot(String projectId) throws Exception {
        Map<String, String> latestVersions = new HashMap<>();
        String url = "https://api.spiget.org/v2/resources/" + projectId + "/versions/latest";

        HttpRequest request = HttpRequest.newBuilder().timeout(java.time.Duration.ofSeconds(15)).uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonObject vObj = JsonParser.parseString(response.body()).getAsJsonObject();
            latestVersions.put("release", vObj.get("name").getAsString());
        }
        return latestVersions;
    }
}
