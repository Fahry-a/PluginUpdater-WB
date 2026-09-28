package me.webbeck.pluginUpdater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class ConfigManager {
    private final PluginUpdater plugin;
    private String mcVersion;
    private String serverTypeOverride;
    private List<String> allowedPlayers;
    private String trackingType;

    /** Modrinth project for Geyser-Spigot (loaders paper/spigot, file Geyser-Spigot.jar). */
    public static final String GEYSER_MODRINTH_ID = "wKkoqHrH";

    public ConfigManager(PluginUpdater plugin) {
        this.plugin = plugin;
    }

    /** True for either plugin.yml name Geyser reports (jar is always Geyser-Spigot.jar). */
    public static boolean isGeyserPluginName(String name) {
        return name != null
                && (name.equalsIgnoreCase("geyser") || name.equalsIgnoreCase("geyser-spigot"));
    }

    public void syncConfig() {
        plugin.reloadConfig();
        plugin.reloadPluginsConfig();

        mcVersion = resolveMinecraftVersion();
        serverTypeOverride = plugin.getConfig().getString("server-type-override", "paper");
        trackingType = plugin.getConfig().getString("tracking-type", "all");
        allowedPlayers = plugin.getConfig().getStringList("allowed-players");
        if (!plugin.getConfig().contains("minecraft-version")) plugin.getConfig().set("minecraft-version", "");
        if (!plugin.getConfig().contains("server-type-override")) plugin.getConfig().set("server-type-override", "paper");
        if (!plugin.getConfig().contains("tracking-type")) plugin.getConfig().set("tracking-type", "all");
        if (!plugin.getConfig().contains("github-token")) plugin.getConfig().set("github-token", "");
        if (!plugin.getConfig().contains("allowed-players")) plugin.getConfig().set("allowed-players", new ArrayList<>(Collections.singletonList("AdminName")));

        if (!plugin.getConfig().contains("geyser-addons")) {
            ConfigurationSection gSec = plugin.getConfig().createSection("geyser-addons");
            gSec.set("enabled", false);
            gSec.set("Geyser", true);
            gSec.set("Floodgate", true);
            gSec.set("MCXboxBroadcast", true);
        }
        ensureGeyserAddonDefaults();

        ConfigurationSection pluginsSection = plugin.getPluginsConfig();

        // Geyser is always managed by the normal plugin pipeline, but its source is fixed to Modrinth.
        // Repair legacy/custom source settings so /upd plugin geyser and /upd check use the same source.
        org.bukkit.plugin.Plugin loadedGeyser = Bukkit.getPluginManager().getPlugin("Geyser");
        if (loadedGeyser != null) {
            ConfigurationSection geyserSection = pluginsSection.getConfigurationSection(loadedGeyser.getName());
            if (geyserSection == null) {
                geyserSection = pluginsSection.createSection(loadedGeyser.getName());
            }
            geyserSection.set("enabled", true);
            geyserSection.set("type", "MODRINTH");
            geyserSection.set("project-id", GEYSER_MODRINTH_ID);
            geyserSection.set("github-repo", null);
            geyserSection.set("custom-url", null);
            if (!geyserSection.contains("allowed-release-types") || geyserSection.getStringList("allowed-release-types").isEmpty()) {
                geyserSection.set("allowed-release-types", Collections.singletonList("beta"));
            }
            geyserSection.set("game-version-filter", false);
        }

        boolean changesMade = false;
        // Floodgate stays out of plugins.yml: its Spigot jar exists on no versioned
        // API (Modrinth bWrNNfkb is Fabric/NeoForge, GitHub has no releases), so it
        // is managed via geyser-addons direct download. Geyser is a regular plugin
        // tracked on Modrinth and intentionally NOT ignored.
        Set<String> ignoredDetectedPlugins = Set.of("floodgate");
        List<String> newlyScannedPlugins = new ArrayList<>();

        Set<String> loadedPlugins = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .map(p -> p.getName().toLowerCase())
                .filter(name -> !ignoredDetectedPlugins.contains(name))
                .collect(Collectors.toSet());

        for (String configPluginName : new ArrayList<>(pluginsSection.getKeys(false))) {
            if (configPluginName.equals("Modrinth-Example") || configPluginName.equals("PluginUpdater-WB") || configPluginName.equals("GitHub-Example") || configPluginName.equals("CustomPlugin-Example") || configPluginName.equals("HangarPlugin-Example") || configPluginName.equals("SpigotPlugin-Example")) {
                continue;
            }
            if (!loadedPlugins.contains(configPluginName.toLowerCase()) || ignoredDetectedPlugins.contains(configPluginName.toLowerCase())) {
                if (pluginsSection.getBoolean(configPluginName + ".installed", true)) {
                    pluginsSection.set(configPluginName + ".installed", false);
                    changesMade = true;
                    plugin.getLogger().info("Marked " + configPluginName + " as not installed (kept in plugins.yml).");
                }
            }
        }

        for (var plugin : Bukkit.getPluginManager().getPlugins()) {
            String name = plugin.getName();
            if (name.equalsIgnoreCase(this.plugin.getName())) continue;
            if (ignoredDetectedPlugins.contains(name.toLowerCase())) continue;

            if (!pluginsSection.contains(name)) {
                ConfigurationSection pSec = pluginsSection.createSection(name);
                pSec.set("enabled", true);
                if (isGeyserPluginName(name)) {
                    // Pin Geyser to its Modrinth project: every published version is
                    // version_type beta, and no build targets the newest game version,
                    // so seed channel beta and skip the game_versions filter (saves
                    // one request per check; the unfiltered fallback would run anyway).
                    pSec.set("type", "MODRINTH");
                    pSec.set("project-id", GEYSER_MODRINTH_ID);
                    pSec.set("allowed-release-types", Collections.singletonList("beta"));
                    pSec.set("game-version-filter", false);
                    plugin.getLogger().info("Tracked Geyser via Modrinth (" + GEYSER_MODRINTH_ID + ", channel beta).");
                } else {
                    pSec.set("type", "MODRINTH");
                    pSec.set("project-id", name.toLowerCase().replace(" ", "-"));
                    if (trackingType != null) {
                        if (trackingType.equalsIgnoreCase("all")) {
                            pSec.set("allowed-release-types", Arrays.asList("release", "beta", "alpha"));
                        } else {
                            pSec.set("allowed-release-types", Collections.singletonList(trackingType.toLowerCase()));
                        }
                    } else {
                        pSec.set("allowed-release-types", Collections.singletonList("release"));
                    }
                    newlyScannedPlugins.add(name);
                }
                pSec.set("current-version", plugin.getDescription().getVersion());
                changesMade = true;
            } else {
                ConfigurationSection pSec = pluginsSection.getConfigurationSection(name);
                if (pSec != null && pSec.contains("installed")) {
                    pSec.set("installed", null);
                    changesMade = true;
                }
                if (pSec != null && !pSec.contains("allowed-release-types")) {
                    if (trackingType != null) {
                        if (trackingType.equalsIgnoreCase("all")) {
                            pSec.set("allowed-release-types", Arrays.asList("release", "beta", "alpha"));
                        } else {
                            pSec.set("allowed-release-types", Collections.singletonList(trackingType.toLowerCase()));
                        }
                    } else {
                        pSec.set("allowed-release-types", Collections.singletonList("release"));
                    }
                    changesMade = true;
                }
                if (pSec != null) {
                    String currentConfigVersion = pSec.getString("current-version", "");
                    if (!currentConfigVersion.equals(plugin.getDescription().getVersion())) {
                        pSec.set("current-version", plugin.getDescription().getVersion());
                        changesMade = true;
                    }
                }
            }
        }

        sortPluginConfig(pluginsSection);

        // tracking-type is only the default for plugins that have no explicit
        // allowed-release-types (applied above). It never overwrites per-plugin choices.
        if (changesMade) {
            saveAndFormatConfig();
        }

        List<String> toResolve = new ArrayList<>(newlyScannedPlugins);
        for (String key : pluginsSection.getKeys(false)) {
            if (pluginsSection.getBoolean(key + ".resolve-failed", false) && !toResolve.contains(key)) {
                toResolve.add(key);
            }
        }

        if (!toResolve.isEmpty()) {
            // Snapshot Yaml on this (main) thread; the worker never touches Yaml off-thread.
            Map<String, String> snapshotTypes = new HashMap<>();
            Map<String, String> snapshotIds = new HashMap<>();
            for (String pName : toResolve) {
                String currentType = plugin.getPluginsConfig().getString(pName + ".type", "MODRINTH");
                String currentId = currentType.equals("GITHUB")
                        ? plugin.getPluginsConfig().getString(pName + ".github-repo", "")
                        : plugin.getPluginsConfig().getString(pName + ".project-id", "");
                snapshotTypes.put(pName, currentType);
                snapshotIds.put(pName, currentId != null ? currentId : "");
            }
            String loaderFacet = modrinthLoaderFacet();
            java.util.concurrent.Executor exec;
            try {
                exec = plugin.getIoExecutors() != null ? plugin.getIoExecutors().checkPool() : java.util.concurrent.ForkJoinPool.commonPool();
            } catch (Exception e) {
                exec = java.util.concurrent.ForkJoinPool.commonPool();
            }
            CompletableFuture.runAsync(() -> {
                Map<String, String> resolvedIds = new HashMap<>();
                Map<String, String> resolvedTypes = new HashMap<>();
                List<String> stillFailing = new ArrayList<>();

                ModrinthResolver resolver = new ModrinthResolver(plugin.getHttpClient(), loaderFacet);
                for (String pName : toResolve) {
                    String currentType = snapshotTypes.getOrDefault(pName, "MODRINTH");
                    String currentId = snapshotIds.getOrDefault(pName, "");

                    // A configured ID that still answers 200 is left alone.
                    if (currentType.equals("MODRINTH") && !currentId.isBlank()) {
                        Boolean valid = resolver.validateId(currentId);
                        if (Boolean.TRUE.equals(valid)) continue;
                        if (valid == null) continue; // network failed - retry next sync
                    }

                    boolean done = false;
                    try {
                        ModrinthResolver.Resolution resolution = resolver.resolve(pName);
                        if (resolution.found) {
                            resolvedIds.put(pName, resolution.id);
                            resolvedTypes.put(pName, "MODRINTH");
                            done = true;
                        } else {
                            plugin.getLogger().warning("Could not confidently match " + pName + " on Modrinth."
                                    + formatCandidates(resolution.candidates)
                                    + " Use /upd plugin id " + pName + " <Modrinth|Hangar|Spigot|GitHub|Custom> <id/repo/url>.");
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Modrinth resolve failed for " + pName + ": " + e.getMessage());
                    }
                    if (done) continue;

                    try {
                        String realSpigotId = getRealSpigotId(pName);
                        if (realSpigotId != null) {
                            resolvedIds.put(pName, realSpigotId);
                            resolvedTypes.put(pName, "SPIGOT");
                            continue;
                        }
                        List<SpigotCandidate> options = spigotCandidates(pName);
                        if (!options.isEmpty()) {
                            plugin.getLogger().warning("No exact Spigot match for " + pName + "."
                                    + describeSpigotCandidates(options)
                                    + " Apply one with /upd plugin id " + pName + " Spigot <id>.");
                        } else {
                            plugin.getLogger().warning("No public source found for " + pName + ". " + privateJarHint(pName));
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Spigot resolve failed for " + pName + ": " + e.getMessage());
                    }
                    stillFailing.add(pName);
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (Map.Entry<String, String> entry : resolvedIds.entrySet()) {
                        String pluginName = entry.getKey();
                        String resolvedId = entry.getValue();
                        String sourceType = resolvedTypes.getOrDefault(pluginName, "MODRINTH");
                        plugin.getPluginsConfig().set(pluginName + ".type", sourceType);
                        plugin.getPluginsConfig().set(pluginName + ".project-id", resolvedId);
                        plugin.getPluginsConfig().set(pluginName + ".github-repo", null);
                        plugin.getPluginsConfig().set(pluginName + ".custom-url", null);
                        plugin.getPluginsConfig().set(pluginName + ".resolve-failed", null);
                        plugin.getLogger().info("Auto-resolved precise " + sourceType + " ID for " + pluginName + ": " + resolvedId);
                    }
                    for (String pluginName : stillFailing) {
                        if (!resolvedIds.containsKey(pluginName)) {
                            plugin.getPluginsConfig().set(pluginName + ".resolve-failed", true);
                        }
                    }
                    if (!resolvedIds.isEmpty() || !stillFailing.isEmpty()) {
                        saveAndFormatConfig();
                    }
                });
            }, exec);
        }
    }

    private void ensureGeyserAddonDefaults() {
        try {
            var cfg = plugin.getConfig();
            // Geyser left geyser-addons: it is a regular plugin tracked on Modrinth
            // (see plugins.yml). Drop legacy keys so nothing double-downloads it.
            cfg.set("geyser-addons.Geyser", null);
            cfg.set("geyser-addons.Geyser-url", null);
            cfg.set("geyser-addons.Geyser-file", null);
            // Floodgate: Spigot jar lives on no versioned API, direct download only.
            if (!cfg.contains("geyser-addons.Floodgate-url")) {
                cfg.set("geyser-addons.Floodgate-url",
                        "https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot");
            }
            if (!cfg.contains("geyser-addons.Floodgate-file")) cfg.set("geyser-addons.Floodgate-file", "floodgate.jar");
            // MCXboxBroadcast: GitHub release with an explicit asset pin (the release
            // also ships a Standalone jar, so fuzzy matching would refuse to choose).
            if (!cfg.contains("geyser-addons.MCXboxBroadcast-repo")) {
                cfg.set("geyser-addons.MCXboxBroadcast-repo", "MCXboxBroadcast/Broadcaster");
            }
            if (!cfg.contains("geyser-addons.MCXboxBroadcast-asset")) {
                cfg.set("geyser-addons.MCXboxBroadcast-asset", "MCXboxBroadcastExtension.jar");
            }
            if (!cfg.contains("geyser-addons.MCXboxBroadcast-file")) cfg.set("geyser-addons.MCXboxBroadcast-file", "MCXboxBroadcastExtension.jar");
            cfg.set("geyser-addons.MCXboxBroadcast-url", null);
        } catch (Exception ignored) {
        }
    }

    private void sortPluginConfig(ConfigurationSection pluginsSection) {
        if (pluginsSection == null) return;

        List<String> allKeys = new ArrayList<>(pluginsSection.getKeys(false));
        List<String> scannedKeys = new ArrayList<>();
        List<String> finalSortedKeys = new ArrayList<>();

        List<String> fixedExamples = Arrays.asList("Modrinth-Example", "PluginUpdater-WB", "GitHub-Example", "HangarPlugin-Example", "SpigotPlugin-Example", "CustomPlugin-Example");

        for (String ex : fixedExamples) {
            if (allKeys.contains(ex)) {
                finalSortedKeys.add(ex);
            }
        }

        for (String key : allKeys) {
            if (key.endsWith("-Example") && !fixedExamples.contains(key)) {
                finalSortedKeys.add(key);
            } else if (!key.endsWith("-Example")) {
                scannedKeys.add(key);
            }
        }

        scannedKeys.sort(String.CASE_INSENSITIVE_ORDER);
        finalSortedKeys.addAll(scannedKeys);

        Map<String, Object> tempMap = new LinkedHashMap<>();
        for (String key : finalSortedKeys) {
            ConfigurationSection sub = pluginsSection.getConfigurationSection(key);
            if (sub != null) {
                tempMap.put(key, new LinkedHashMap<>(sub.getValues(false)));
            } else {
                tempMap.put(key, pluginsSection.get(key));
            }
        }

        for (String key : new ArrayList<>(pluginsSection.getKeys(false))) {
            pluginsSection.set(key, null);
        }
        for (Map.Entry<String, Object> entry : tempMap.entrySet()) {
            if (entry.getValue() instanceof Map) {
                ConfigurationSection p = pluginsSection.createSection(entry.getKey());
                for (Map.Entry<?, ?> val : ((Map<?, ?>) entry.getValue()).entrySet()) {
                    p.set(String.valueOf(val.getKey()), val.getValue());
                }
            } else {
                pluginsSection.set(entry.getKey(), entry.getValue());
            }
        }
    }

    public void saveAndFormatConfig() {
        plugin.saveConfig();
        plugin.savePluginsConfig();
    }

    /** File I/O off the main thread; in-memory mutation must happen before calling. */
    public void saveAndFormatConfigAsync() {
        try {
            IoExecutors io = plugin.getIoExecutors();
            if (io != null) {
                io.configPool().execute(() -> {
                    synchronized (plugin) {
                        try {
                            plugin.saveConfig();
                        } catch (Exception ignored) {
                        }
                        try {
                            plugin.savePluginsConfig();
                        } catch (Exception ignored) {
                        }
                    }
                });
                return;
            }
        } catch (Exception ignored) {
        }
        saveAndFormatConfig();
    }

    /**
     * A configured value always wins. Blank or absent means "detect it", which is the
     * default because a stale version string silently filters every source down to
     * nothing.
     */
    private String resolveMinecraftVersion() {
        String configured = plugin.getConfig().getString("minecraft-version", "");
        if (configured != null && !configured.isBlank()) return configured.trim();
        return detectServerMinecraftVersion();
    }

    private String detectServerMinecraftVersion() {
        try {
            // Paper 26.1+ exposes the game version directly.
            return Bukkit.getMinecraftVersion();
        } catch (Throwable ignored) {
            // Older servers: strip the build suffix from the Bukkit version, which on
            // Paper 26.x looks like "26.3.build.42-stable-R0.1-SNAPSHOT".
            String bukkitVersion = Bukkit.getBukkitVersion();
            int dash = bukkitVersion.indexOf('-');
            String base = dash > 0 ? bukkitVersion.substring(0, dash) : bukkitVersion;
            int build = base.indexOf(".build.");
            return build > 0 ? base.substring(0, build) : base;
        }
    }

    public String modrinthLoaderFacet() {
        String override = serverTypeOverride != null ? serverTypeOverride : "paper";
        if (!override.equalsIgnoreCase("auto")) return override.toLowerCase();
        return Bukkit.getVersion().toLowerCase().contains("paper") ? "paper" : "spigot";
    }

    public ModrinthResolver modrinthResolver() {
        return new ModrinthResolver(plugin.getHttpClient(), modrinthLoaderFacet());
    }

    private static String formatCandidates(List<ModrinthResolver.Candidate> candidates) {
        return ModrinthResolver.describeCandidates(candidates);
    }

    /**
     * Turns Modrinth project IDs into readable "Name (id)" labels using the
     * tracked plugins map. Unknown IDs pass through untouched.
     */
    public String describeModrinthIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "";
        ConfigurationSection section = plugin.getPluginsConfig();
        List<String> parts = new ArrayList<>();
        for (String id : ids) {
            String name = null;
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    if ("MODRINTH".equalsIgnoreCase(section.getString(key + ".type", ""))
                            && id.equals(section.getString(key + ".project-id", ""))) {
                        name = key;
                        break;
                    }
                }
            }
            parts.add(name != null ? name + " (" + id + ")" : id);
        }
        return String.join(", ", parts);
    }

    public String getGitHubToken() {
        return plugin.getConfig().getString("github-token", "");
    }

    public String getMinecraftVersion() {
        return mcVersion;
    }

    public String getServerTypeOverride() {
        return serverTypeOverride;
    }

    public List<String> getAllowedPlayers() {
        return allowedPlayers;
    }

    public boolean hasPermission(CommandSender sender) {
        if (sender.isOp() || sender.hasPermission("pluginupdater.admin")) return true;
        return allowedPlayers != null && allowedPlayers.contains(sender.getName());
    }

    public String getPluginServerType(String pluginName) {
        ConfigurationSection pSec = plugin.getPluginsConfig().getConfigurationSection(pluginName);
        String configured = pSec != null ? pSec.getString("server-type", null) : null;
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return serverTypeOverride != null ? serverTypeOverride : "auto";
    }

    public String getPluginSourceType(String pluginName) {
        ConfigurationSection pSec = plugin.getPluginsConfig().getConfigurationSection(pluginName);
        return pSec != null ? pSec.getString("type", "MODRINTH") : "MODRINTH";
    }

    public String resolvePluginName(String input) {
        ConfigurationSection pSec = plugin.getPluginsConfig();
        if (pSec == null) return null;
        if (pSec.contains(input)) return input;
        return pSec.getKeys(false).stream().filter(k -> k.equalsIgnoreCase(input)).findFirst().orElse(null);
    }

    public List<String> getTrackedChannels(String resolvedName) {
        if (resolvedName.equalsIgnoreCase("all")) {
            return getTrackedChannelsForAllPlugins();
        }

        ConfigurationSection ts = plugin.getPluginsConfig().getConfigurationSection(resolvedName);
        List<String> currentTracked = ts != null ? ts.getStringList("allowed-release-types") : Collections.emptyList();
        if (currentTracked == null || currentTracked.isEmpty()) {
            return Collections.singletonList("release");
        }
        return currentTracked;
    }

    private List<String> getTrackedChannelsForAllPlugins() {
        ConfigurationSection pluginsSec = plugin.getPluginsConfig();
        if (pluginsSec == null) {
            return Collections.singletonList("release");
        }

        Set<String> uniqueChannels = new HashSet<>();
        for (String key : pluginsSec.getKeys(false)) {
            List<String> types = pluginsSec.getStringList(key + ".allowed-release-types");
            if (types == null || types.isEmpty()) {
                types = Collections.singletonList("release");
            }
            if (types.contains("all")) {
                return Collections.singletonList("all");
            }
            if (types.size() > 1) {
                return Collections.singletonList("all");
            }
            uniqueChannels.add(types.get(0).toLowerCase());
            if (uniqueChannels.size() > 1) {
                return Collections.singletonList("all");
            }
        }

        if (uniqueChannels.size() == 1) {
            return Collections.singletonList(uniqueChannels.iterator().next());
        }

        return Collections.singletonList("release");
    }

    public List<String> getEnabledPlugins() {
        ConfigurationSection pSec = plugin.getPluginsConfig();
        if (pSec == null) return Collections.emptyList();
        return pSec.getKeys(false).stream()
                .filter(k -> pSec.getBoolean(k + ".enabled", true))
                .filter(k -> pSec.getBoolean(k + ".installed", true))
                .collect(Collectors.toList());
    }

    public void setPluginIdConfig(CommandSender sender, String pluginName, String source, String projectId) {
        String type = source.toUpperCase(Locale.ROOT);
        String resolvedId = extractIdFromInput(type, projectId);
        String pluginPath = pluginName;

        plugin.getPluginsConfig().set(pluginPath + ".resolve-failed", null);
        plugin.getPluginsConfig().set(pluginPath + ".type", type);
        plugin.getPluginsConfig().set(pluginPath + ".project-id", null);
        plugin.getPluginsConfig().set(pluginPath + ".github-repo", null);
        plugin.getPluginsConfig().set(pluginPath + ".custom-url", null);

        if (type.equals("MODRINTH") || type.equals("HANGAR") || type.equals("SPIGOT")) {
            plugin.getPluginsConfig().set(pluginPath + ".project-id", resolvedId);
            saveAndFormatConfig();
            plugin.sendMsg(sender, ChatColor.GREEN + "Set " + pluginName + " to " + type + " with ID " + resolvedId + ".");
        } else if (type.equals("GITHUB")) {
            plugin.getPluginsConfig().set(pluginPath + ".github-repo", resolvedId);
            saveAndFormatConfig();
            plugin.sendMsg(sender, ChatColor.GREEN + "Set " + pluginName + " to GitHub with repo " + resolvedId + ".");
        } else if (type.equals("CUSTOM")) {
            plugin.getPluginsConfig().set(pluginPath + ".custom-url", resolvedId);
            saveAndFormatConfig();
            plugin.sendMsg(sender, ChatColor.GREEN + "Set " + pluginName + " to Custom with URL " + resolvedId + ".");
        } else {
            plugin.sendMsg(sender, ChatColor.RED + "Invalid source type. Use Modrinth, Hangar, Spigot, GitHub, or Custom.");
        }
    }

    private String extractIdFromInput(String type, String input) {
        if (input == null || !input.contains("://")) return input;
        try {
            URI uri = URI.create(input);
            String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
            String path = uri.getPath() != null ? uri.getPath() : "";
            List<String> segments = Arrays.stream(path.split("/"))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            switch (type.toUpperCase(Locale.ROOT)) {
                case "MODRINTH":
                    if (host.contains("modrinth.com") && segments.size() >= 2)
                        return segments.get(1); // /plugin|mod|project/<slug>
                    break;
                case "HANGAR":
                    if (host.contains("hangar.papermc.io") && segments.size() >= 2
                            && !segments.get(0).equalsIgnoreCase("api"))
                        return segments.get(1); // /Author/<slug>
                    break;
                case "SPIGOT":
                    if (host.contains("spigotmc.org") && segments.size() >= 2
                            && segments.get(0).equalsIgnoreCase("resources")) {
                        String candidate = segments.get(segments.size() - 1);
                        if (candidate.matches("\\d+")) return candidate;
                        if (candidate.contains(".")) {
                            String lastPart = candidate.substring(candidate.lastIndexOf('.') + 1);
                            if (lastPart.matches("\\d+")) return lastPart;
                        }
                    }
                    break;
                case "GITHUB":
                    if (host.contains("github.com") && segments.size() >= 2)
                        return segments.get(0) + "/" + segments.get(1); // owner/repo
                    break;
            }
        } catch (Exception ignored) {}
        return input;
    }

    public String addPluginFromUrl(CommandSender sender, String url) {
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        String type;
        String sourceValue = null;
        String pluginName = null;

        try {
            URI uri = URI.create(url);
            String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
            String path = uri.getPath() != null ? uri.getPath() : "";
            List<String> segments = Arrays.stream(path.split("/"))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            if (host.contains("modrinth.com")) {
                type = "MODRINTH";
                if (segments.size() >= 2 && (segments.get(0).equalsIgnoreCase("project")
                        || segments.get(0).equalsIgnoreCase("plugin")
                        || segments.get(0).equalsIgnoreCase("mod"))) {
                    sourceValue = segments.get(1);
                }
            } else if (host.contains("spigotmc.org")) {
                type = "SPIGOT";
                if (segments.size() >= 2 && segments.get(0).equalsIgnoreCase("resources")) {
                    String candidate = segments.get(segments.size() - 1);
                    if (candidate.matches("\\d+")) {
                        sourceValue = candidate;
                    } else if (candidate.contains(".")) {
                        String[] parts = candidate.split("\\.");
                        String lastPart = parts[parts.length - 1];
                        if (lastPart.matches("\\d+")) {
                            sourceValue = lastPart;
                        }
                    }
                }
            } else if (host.contains("github.com")) {
                type = "GITHUB";
                if (segments.size() >= 2) {
                    sourceValue = segments.get(0) + "/" + segments.get(1);
                }
            } else if (host.contains("hangar.papermc.io")) {
                type = "HANGAR";
                if (segments.size() >= 2 && !segments.get(0).equalsIgnoreCase("api")) {
                    // Web URL: hangar.papermc.io/Author/Slug → use Slug as project ID
                    sourceValue = segments.get(1);
                }
            } else {
                type = "CUSTOM";
                sourceValue = url;
            }

            if (type == null || sourceValue == null) {
                type = "CUSTOM";
                sourceValue = url;
            }

            String slug = sourceValue.contains("/") ? sourceValue.substring(sourceValue.lastIndexOf('/') + 1) : sourceValue;
            for (org.bukkit.plugin.Plugin loaded : Bukkit.getPluginManager().getPlugins()) {
                if (loaded.getName().equalsIgnoreCase(slug) || loaded.getName().equalsIgnoreCase(sourceValue)) {
                    pluginName = loaded.getName();
                    break;
                }
            }

            if (pluginName == null && type.equals("GITHUB") && sourceValue.contains("/")) {
                String repoName = sourceValue.substring(sourceValue.indexOf('/') + 1);
                for (org.bukkit.plugin.Plugin loaded : Bukkit.getPluginManager().getPlugins()) {
                    if (loaded.getName().equalsIgnoreCase(repoName)) {
                        pluginName = loaded.getName();
                        break;
                    }
                }
            }

            if (pluginName == null && !type.equals("CUSTOM")) {
                String fetchedName = fetchPluginNameFromSource(type, sourceValue);
                if (fetchedName != null && !fetchedName.isBlank()) {
                    fetchedName = fetchedName.replaceAll("[^A-Za-z0-9 _-]", "").trim();
                    if (!fetchedName.isEmpty()) {
                        pluginName = fetchedName;
                    }
                }
            }

            if (pluginName == null) {
                pluginName = slug.replaceAll("[^A-Za-z0-9_-]", "");
                if (pluginName.isEmpty()) {
                    plugin.sendMsg(sender, ChatColor.RED + "Could not infer a plugin name from the URL. Please use /upd plugin id instead.");
                    return null;
                }
            }

            ConfigurationSection pluginsSection = plugin.getPluginsConfig();
            if (!pluginsSection.contains(pluginName)) {
                ConfigurationSection pSec = pluginsSection.createSection(pluginName);
                pSec.set("enabled", true);
                pSec.set("current-version", "0.0.0");
                if (trackingType != null && trackingType.equalsIgnoreCase("all")) {
                    pSec.set("allowed-release-types", Arrays.asList("release", "beta", "alpha"));
                } else if (trackingType != null) {
                    pSec.set("allowed-release-types", Collections.singletonList(trackingType.toLowerCase()));
                } else {
                    pSec.set("allowed-release-types", Collections.singletonList("release"));
                }
            }

            plugin.getPluginsConfig().set(pluginName + ".type", type);
            plugin.getPluginsConfig().set(pluginName + ".project-id", type.equals("CUSTOM") || type.equals("GITHUB") ? null : sourceValue);
            plugin.getPluginsConfig().set(pluginName + ".github-repo", type.equals("GITHUB") ? sourceValue : null);
            plugin.getPluginsConfig().set(pluginName + ".custom-url", type.equals("CUSTOM") ? sourceValue : null);
            saveAndFormatConfig();
            plugin.sendMsg(sender, ChatColor.GREEN + "Added plugin config for " + pluginName + " using source " + type + ".");
            if (!type.equals("CUSTOM")) {
                plugin.sendMsg(sender, ChatColor.AQUA + "Verify with /upd plugin info " + pluginName + " and adjust the ID if needed.");
            }
            return pluginName;
        } catch (Exception e) {
            plugin.sendMsg(sender, ChatColor.RED + "Failed to parse plugin URL: " + e.getMessage());
            return null;
        }
    }

    private String fetchPluginNameFromSource(String type, String sourceValue) {
        try {
            if (type.equals("MODRINTH")) {
                return fetchModrinthProjectName(sourceValue);
            }
            if (type.equals("SPIGOT")) {
                return fetchSpigotResourceName(sourceValue);
            }
            if (type.equals("GITHUB")) {
                return fetchGitHubRepoName(sourceValue);
            }
            if (type.equals("HANGAR")) {
                return fetchHangarProjectName(sourceValue);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String fetchModrinthProjectName(String projectId) throws Exception {
        String url = "https://api.modrinth.com/v2/project/" + URLEncoder.encode(projectId, StandardCharsets.UTF_8.toString());
        HttpRequest request = HttpRequest.newBuilder().timeout(java.time.Duration.ofSeconds(15)).uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = plugin.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (json.has("title")) return json.get("title").getAsString();
        if (json.has("name")) return json.get("name").getAsString();
        return null;
    }

    private String fetchSpigotResourceName(String resourceId) throws Exception {
        String url = "https://api.spiget.org/v2/resources/" + URLEncoder.encode(resourceId, StandardCharsets.UTF_8.toString());
        HttpRequest request = HttpRequest.newBuilder().timeout(java.time.Duration.ofSeconds(15)).uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = plugin.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.has("name") ? json.get("name").getAsString() : null;
    }

    private String fetchGitHubRepoName(String repo) throws Exception {
        String url = "https://api.github.com/repos/" + repo;
        HttpRequest.Builder builder = HttpRequest.newBuilder().timeout(java.time.Duration.ofSeconds(15)).uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB");
        String token = getGitHubToken();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token.trim());
        }
        HttpResponse<String> response = plugin.getHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (json.has("name")) return json.get("name").getAsString();
        if (json.has("full_name")) return json.get("full_name").getAsString();
        return null;
    }

    private String fetchHangarProjectName(String projectId) throws Exception {
        String url = "https://hangar.papermc.io/api/v1/projects/" + URLEncoder.encode(projectId, StandardCharsets.UTF_8.toString());
        HttpRequest request = HttpRequest.newBuilder().timeout(java.time.Duration.ofSeconds(15)).uri(URI.create(url)).header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<String> response = plugin.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (json.has("pluginName")) return json.get("pluginName").getAsString();
        if (json.has("name")) return json.get("name").getAsString();
        if (json.has("title")) return json.get("title").getAsString();
        return null;
    }

    public void setPluginToggle(CommandSender sender, String pluginName, boolean enabled) {
        String resolvedName = resolvePluginName(pluginName);
        if (resolvedName == null) {
            plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + pluginName + "' not found in config.");
            return;
        }

        plugin.getPluginsConfig().set(resolvedName + ".enabled", enabled);
        saveAndFormatConfig();
        plugin.sendMsg(sender, ChatColor.GREEN + resolvedName + " is now " + (enabled ? "ENABLED" : "DISABLED") + " for updates.");

        if (enabled) {
            plugin.getUpdateChecker().runUpdateCheck(Bukkit.getConsoleSender(), false, null);
        }
    }

    public void setServerTypeOverride(String type) {
        plugin.getConfig().set("server-type-override", type.toLowerCase());
        saveAndFormatConfig();
        serverTypeOverride = type.toLowerCase();
    }

    public String getPrettyServerType(String serverType) {
        if (serverType == null) return "UNKNOWN";
        if (!serverType.equalsIgnoreCase("auto")) {
            return serverType.toUpperCase();
        }
        String detected = Bukkit.getVersion().toLowerCase().contains("paper") ? "Paper" : "Spigot";
        return "AUTO (" + detected + ")";
    }

    public static final class SpigotCandidate {
        public final String id;
        public final String name;

        public SpigotCandidate(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private JsonArray searchSpigotResources(String pluginName) throws Exception {
        String query = URLEncoder.encode(pluginName, StandardCharsets.UTF_8.toString());
        String searchUrl = "https://api.spiget.org/v2/search/resources/" + query + "?size=10";

        HttpRequest searchRequest = HttpRequest.newBuilder().timeout(java.time.Duration.ofSeconds(15))
                .uri(URI.create(searchUrl))
                .header("User-Agent", "PluginUpdater-WB")
                .build();

        HttpResponse<String> searchResponse = plugin.getHttpClient().send(searchRequest, HttpResponse.BodyHandlers.ofString());
        if (searchResponse.statusCode() != 200) return null;
        return JsonParser.parseString(searchResponse.body()).getAsJsonArray();
    }

    public String getRealSpigotId(String pluginName) throws Exception {
        JsonArray results = searchSpigotResources(pluginName);
        if (results == null) return null;
        String normalizedName = pluginName.toLowerCase().replace(" ", "");

        for (JsonElement element : results) {
            JsonObject resource = element.getAsJsonObject();
            if (resource.has("name") && resource.has("id")) {
                String resourceName = resource.get("name").getAsString();
                String normalizedResourceName = resourceName.toLowerCase().replace(" ", "");
                if (normalizedResourceName.equals(normalizedName) || resourceName.equalsIgnoreCase(pluginName)) {
                    return resource.get("id").getAsString();
                }
            }
        }

        return null;
    }

    static boolean spigotCloseMatch(String normQuery, String normResource) {
        if (normQuery.isEmpty() || normResource.isEmpty() || normQuery.equals(normResource)) return false;
        return normResource.contains(normQuery) || normQuery.contains(normResource);
    }

    /**
     * Inexact Spiget matches for when the exact lookup fails. Never auto-applied;
     * shown to the admin so a human picks the right resource.
     */
    public List<SpigotCandidate> spigotCandidates(String pluginName) {
        List<SpigotCandidate> out = new ArrayList<>();
        try {
            JsonArray results = searchSpigotResources(pluginName);
            if (results == null) return out;
            String norm = pluginName.toLowerCase().replaceAll("[^a-z0-9]", "");
            for (JsonElement element : results) {
                JsonObject resource = element.getAsJsonObject();
                if (!resource.has("name") || !resource.has("id")) continue;
                String resourceName = resource.get("name").getAsString();
                String resourceNorm = resourceName.toLowerCase().replaceAll("[^a-z0-9]", "");
                if (spigotCloseMatch(norm, resourceNorm)) {
                    out.add(new SpigotCandidate(resource.get("id").getAsString(), resourceName));
                    if (out.size() >= 5) break;
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static String describeSpigotCandidates(List<SpigotCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return "";
        StringBuilder out = new StringBuilder(" Spigot close matches:");
        for (SpigotCandidate candidate : candidates) {
            out.append(" '").append(candidate.name).append("' (").append(candidate.id).append(")");
        }
        return out.toString();
    }

    public static String privateJarHint(String pluginName) {
        return "If '" + pluginName + "' is a private/custom jar, point it at a URL: /upd plugin id " + pluginName
                + " Custom <url> - or stop tracking it: /upd plugin toggle " + pluginName + " false";
    }
}
