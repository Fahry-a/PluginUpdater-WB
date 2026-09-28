package me.webbeck.pluginUpdater;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PluginUpdater extends JavaPlugin implements Listener {
    private HttpClient httpClient;
    private IoExecutors ioExecutors;
    private final Map<String, UpdateInfo> pendingUpdates = new ConcurrentHashMap<>();
    private final Map<String, UpdateInfo> unfilteredUpdates = new ConcurrentHashMap<>();
    private final Map<String, CheckError> checkErrors = new ConcurrentHashMap<>();
    private volatile boolean initialCheckComplete = false;

    // Holds plugin names awaiting destructive confirmation, keyed by sender name.
    private final Map<String, PendingDeletion> pendingDeletions = new ConcurrentHashMap<>();
    private final Object deletionLock = new Object();

    private File pluginsFile;
    private YamlConfiguration pluginsConfig;

    private ConfigManager configManager;
    private UpdateChecker updateChecker;
    private UpdateDownloader updateDownloader;
    private GeyserManager geyserManager;
    private CommandHandler commandHandler;

    @Override
    public void onEnable() {
        httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        ioExecutors = new IoExecutors();

        saveDefaultConfig();
        getConfig().options().header("PluginUpdater-WB settings.\nEdit while the server is stopped, then run /upd reload (or restart).");
        initPluginsConfig();
        ensureSelfTrackingConfig();

        configManager = new ConfigManager(this);
        updateChecker = new UpdateChecker(this, configManager, httpClient);
        updateDownloader = new UpdateDownloader(this, updateChecker, configManager);
        SourceRegistry registry = new SourceRegistry(updateChecker);
        updateChecker.setRegistry(registry);
        geyserManager = new GeyserManager(this, httpClient, updateChecker);
        commandHandler = new CommandHandler(this, configManager, updateChecker, updateDownloader, geyserManager);

        getCommand("updater").setExecutor(commandHandler);
        getCommand("updater").setTabCompleter(commandHandler);
        getServer().getPluginManager().registerEvents(this, this);
        // Process any deletions that were scheduled from a previous session
        processPendingDeletions();

        // Register shutdown hook to ensure deletion at JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            getLogger().info("Executing shutdown hook for pending deletions...");
            performFinalDeletions();
        }, "PluginUpdater-ShutdownDeletions"));

        getServer().getScheduler().runTaskLater(this, () -> {
            configManager.syncConfig();
            updateChecker.runUpdateCheck(Bukkit.getConsoleSender(), false, null);
        }, 1L);

        scheduleAutomaticSelfChecks();
    }

    @Override
    public void onDisable() {
        // Attempt to process any pending deletions during disable
        processPendingDeletions();
        if (ioExecutors != null) ioExecutors.shutdown();
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public IoExecutors getIoExecutors() {
        return ioExecutors;
    }

    /** For unit tests that construct managers without onEnable. */
    void setIoExecutors(IoExecutors executors) {
        this.ioExecutors = executors;
    }

    public Map<String, UpdateInfo> getPendingUpdates() {
        return pendingUpdates;
    }

    public Map<String, UpdateInfo> getUnfilteredUpdates() {
        return unfilteredUpdates;
    }

    public Map<String, CheckError> getCheckErrors() {
        return checkErrors;
    }

    /**
     * Resolves the staging folder the same way Paper does: {@code settings.update-folder}
     * from bukkit.yml, relative to the plugins directory. Defaults to {@code update}.
     */
    public File getUpdateFolder() {
        return new File(getDataFolder().getParentFile(), resolveUpdateFolderName());
    }

    private String resolveUpdateFolderName() {
        try {
            File pluginsDir = getDataFolder().getParentFile();
            File serverRoot = pluginsDir != null ? pluginsDir.getParentFile() : null;
            if (serverRoot != null) {
                File bukkitYml = new File(serverRoot, "bukkit.yml");
                if (bukkitYml.isFile()) {
                    String name = org.bukkit.configuration.file.YamlConfiguration
                            .loadConfiguration(bukkitYml).getString("settings.update-folder", "update");
                    if (name != null && !name.isBlank()) return name.trim();
                }
            }
        } catch (Exception e) {
            getLogger().warning("Failed to read update-folder from bukkit.yml, using 'update': " + e.getMessage());
        }
        return "update";
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public UpdateDownloader getUpdateDownloader() {
        return updateDownloader;
    }

    public void sendMsg(org.bukkit.command.CommandSender sender, String msg) {
        Bukkit.getScheduler().runTask(this, () ->
                sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(msg))
        );
    }

    public void updateActionBar(org.bukkit.command.CommandSender sender, String msg) {
        if (sender instanceof Player player) {
            Bukkit.getScheduler().runTask(this, () ->
                    player.sendActionBar(Component.text(msg, NamedTextColor.AQUA))
            );
        }
    }

    public void sendInteractiveListMsg(org.bukkit.command.CommandSender sender, String name, String oldV, String newV, boolean needsUpdate) {
        Bukkit.getScheduler().runTask(this, () -> {
            Component tc = Component.text(name, NamedTextColor.AQUA)
                    .append(Component.text(": ", NamedTextColor.GRAY))
                    .append(Component.text(oldV, NamedTextColor.GRAY))
                    .append(Component.text(" -> ", NamedTextColor.GRAY))
                    .append(Component.text(newV, NamedTextColor.AQUA))
                    .append(Component.text(" ", NamedTextColor.GRAY));

            if (needsUpdate) {
                if (sender instanceof Player) {
                    Component btn = Component.text("[CLICK TO UPDATE]", NamedTextColor.GREEN)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/upd run " + name))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Click to download update", NamedTextColor.YELLOW)));
                    tc = tc.append(btn);
                } else {
                    tc = tc.append(Component.text("[Use /upd run " + name + "]", NamedTextColor.GREEN));
                }
            } else {
                tc = tc.append(Component.text("[UP TO DATE]", NamedTextColor.GREEN));
            }

            sender.sendMessage(tc);
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (configManager.hasPermission(p)) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!pendingUpdates.isEmpty()) {
                    p.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.GOLD + "[PluginUpdater] " + ChatColor.YELLOW + "There are " + pendingUpdates.size() + " plugin updates pending! Use /upd list"));
                } else if (initialCheckComplete) {
                    p.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.GOLD + "[PluginUpdater] " + ChatColor.GREEN + "All plugins are up to date!"));
                }
            }, 40L);
        }
    }

    public void setInitialCheckComplete() {
        this.initialCheckComplete = true;
    }

    public static final class PendingDeletion {
        public final String pluginName;
        public final long createdAt;

        PendingDeletion(String pluginName) {
            this.pluginName = pluginName;
            this.createdAt = System.currentTimeMillis();
        }

        boolean expired() {
            return System.currentTimeMillis() - createdAt > 60_000;
        }
    }

    public void setPendingDeletion(org.bukkit.command.CommandSender sender, String pluginName) {
        pendingDeletions.put(sender.getName(), new PendingDeletion(pluginName));
    }

    public String consumePendingDeletion(org.bukkit.command.CommandSender sender) {
        PendingDeletion pending = pendingDeletions.remove(sender.getName());
        if (pending == null || pending.expired()) return null;
        return pending.pluginName;
    }

    public YamlConfiguration getPluginsConfig() {
        return pluginsConfig;
    }

    public void savePluginsConfig() {
        try {
            pluginsConfig.save(pluginsFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save plugins.yml: " + e.getMessage());
        }
    }

    public void reloadPluginsConfig() {
        pluginsConfig = YamlConfiguration.loadConfiguration(pluginsFile);
        pluginsConfig.options().header(PLUGINS_HEADER);
    }

    private static final String PLUGINS_HEADER = "Auto-generated by PluginUpdater-WB.\n"
            + "Per-plugin tracking state - safe to edit while the server is stopped.\n"
            + "Entries marked installed: false belong to plugins not currently on the server.";

    /**
     * Per-plugin tracking state lives in plugins.yml, separate from config.yml settings.
     * Migrates the legacy config.yml "plugins:" section once, then leaves config.yml alone.
     */
    private void initPluginsConfig() {
        pluginsFile = new File(getDataFolder(), "plugins.yml");
        if (!pluginsFile.exists()) {
            pluginsConfig = new YamlConfiguration();
            pluginsConfig.options().header(PLUGINS_HEADER);
            ConfigurationSection legacy = getConfig().getConfigurationSection("plugins");
            if (legacy != null) {
                for (String key : legacy.getKeys(false)) {
                    ConfigurationSection sub = legacy.getConfigurationSection(key);
                    if (sub == null) continue;
                    ConfigurationSection dest = pluginsConfig.createSection(key);
                    for (Map.Entry<String, Object> value : sub.getValues(false).entrySet()) {
                        dest.set(value.getKey(), value.getValue());
                    }
                }
            }
            savePluginsConfig();
            getConfig().set("plugins", null);
            saveConfig();
        } else {
            reloadPluginsConfig();
        }
    }

    /**
     * Keeps PluginUpdater-WB itself in the normal plugin tracking pipeline.
     * The repository and release format are fixed to this fork so a new GitHub
     * Release can be discovered without requiring a manual /upd plugin command.
     */
    private void ensureSelfTrackingConfig() {
        String pluginName = getDescription().getName();
        ConfigurationSection section = pluginsConfig.getConfigurationSection(pluginName);
        if (section == null) {
            section = pluginsConfig.createSection(pluginName);
        }

        boolean enabled = getConfig().getBoolean("self-update.enabled", true);
        section.set("enabled", enabled);
        section.set("installed", true);
        section.set("type", "GITHUB");
        section.set("github-repo", "Fahry-a/PluginUpdater-WB");
        section.set("project-id", null);
        section.set("custom-url", null);
        section.set("allowed-release-types", java.util.Collections.singletonList("release"));

        String currentVersion = getDescription().getVersion();
        section.set("current-version", currentVersion);

        savePluginsConfig();
    }

    /**
     * Automatically checks for the updater itself at a configurable interval.
     * Only the updater's own pending update is auto-downloaded; other plugins
     * still require their normal update command.
     */
    private void scheduleAutomaticSelfChecks() {
        long minutes = getConfig().getLong("self-update.check-interval-minutes", 360L);
        if (minutes <= 0L) return;

        long ticks;
        try {
            ticks = Math.multiplyExact(minutes, 60L * 20L);
        } catch (ArithmeticException e) {
            getLogger().warning("self-update.check-interval-minutes is too large; automatic checks disabled.");
            return;
        }

        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!getConfig().getBoolean("self-update.enabled", true)) return;
            updateChecker.runUpdateCheck(Bukkit.getConsoleSender(), false, null);
        }, ticks, ticks);
    }

    /**
     * Called after an update check completes. If a newer official GitHub
     * release exists, stage it through the normal hardened downloader.
     * Paper applies files in the update folder on the next server restart.
     */
    public void autoApplySelfUpdate() {
        if (!getConfig().getBoolean("self-update.enabled", true)
                || !getConfig().getBoolean("self-update.auto-download", true)) {
            return;
        }

        String pluginName = getDescription().getName();
        UpdateInfo info = pendingUpdates.get(pluginName.toLowerCase());
        if (info == null) return;

        getLogger().info("Automatic self-update available: " + info.oldVersion + " -> " + info.newVersion
                + ". Downloading to Paper's update folder.");

        updateDownloader.applyUpdates(Bukkit.getConsoleSender(), java.util.Collections.singletonList(info));
    }

    public void appendPendingDeletion(String entry) {
        synchronized (deletionLock) {
            try {
                File pendingFile = new File(getDataFolder(), "pending-deletions.txt");
                java.nio.file.Files.writeString(pendingFile.toPath(), entry + System.lineSeparator(),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {
                getLogger().warning("Failed to schedule deletion entry '" + entry + "': " + e.getMessage());
            }
        }
    }

    private void processPendingDeletions() {
        File pendingFile = new File(getDataFolder(), "pending-deletions.txt");
        if (!pendingFile.exists()) return;

        synchronized (deletionLock) {
        File pluginsFolder = getDataFolder().getParentFile();
        java.util.List<String> remaining = new java.util.ArrayList<>();
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(pendingFile.toPath());
            for (String line : lines) {
                String entry = line == null ? "" : line.trim();
                if (entry.startsWith("plugin:")) {
                    disableExactPlugin(entry.substring(7));
                }
            }
            for (String line : lines) {
                String entry = line == null ? "" : line.trim();
                if (entry.isEmpty()) continue;
                if (entry.startsWith("plugin:")) continue;
                boolean success = false;
                try {
                    if (entry.startsWith("jar:")) {
                        String name = entry.substring(4);
                        File target = new File(pluginsFolder, name);
                        if (target.exists()) {
                            disableExactPlugin(stripJarSuffix(name));
                            
                            success = attemptDeletion(target, 3);
                            
                            if (success) {
                                getLogger().info("Deleted scheduled plugin jar: " + name);
                            } else {
                                getLogger().warning("Could not delete jar (queued for JVM shutdown): " + name);
                                target.deleteOnExit();
                                success = true;
                            }
                        } else {
                            getLogger().info("Scheduled jar not found: " + name);
                            success = true;
                        }
                    } else if (entry.startsWith("dir:")) {
                        String dirName = entry.substring(4);
                        File targetDir = new File(pluginsFolder, dirName);
                        if (targetDir.exists()) {
                            // Recoverable: move data folder aside instead of recursive delete.
                            success = archiveDataFolder(targetDir, dirName);
                        } else {
                            getLogger().info("Scheduled plugin data folder not found: " + dirName);
                            success = true;
                        }
                    } else {
                        File target = new File(pluginsFolder, entry);
                        if (target.exists()) {
                            success = attemptDeletion(target, 3);
                            if (success) {
                                getLogger().info("Deleted scheduled plugin jar: " + entry);
                            } else {
                                target.deleteOnExit();
                                success = true;
                            }
                        } else {
                            success = true;
                        }
                    }
                } catch (Exception e) {
                    getLogger().warning("Failed to process scheduled deletion entry '" + entry + "': " + e.getMessage());
                    success = false;
                }

                if (!success) {
                    remaining.add(entry);
                }
            }
        } catch (Exception e) {
            getLogger().warning("Failed to process pending deletions: " + e.getMessage());
        }

        try {
            if (remaining.isEmpty()) java.nio.file.Files.deleteIfExists(pendingFile.toPath());
            else java.nio.file.Files.write(pendingFile.toPath(), remaining);
        } catch (Exception ignored) {}
        }
    }

    /** Moves a plugin data folder to deleted-backups/ so a mistaken confirm is recoverable. */
    private boolean archiveDataFolder(File targetDir, String dirName) {
        try {
            File archiveRoot = new File(getDataFolder(), "deleted-backups");
            if (!archiveRoot.exists()) archiveRoot.mkdirs();
            String safe = dirName.replaceAll("[^A-Za-z0-9._-]", "_");
            File dest = new File(archiveRoot, safe + "-" + System.currentTimeMillis());
            java.nio.file.Files.move(targetDir.toPath(), dest.toPath());
            getLogger().info("Archived plugin data folder " + dirName + " to " + dest.getName() + " (recoverable).");
            return true;
        } catch (Exception e) {
            getLogger().warning("Failed to archive folder " + dirName + ": " + e.getMessage());
            return false;
        }
    }

    private String stripJarSuffix(String jarName) {
        String lower = jarName.toLowerCase();
        return lower.endsWith(".jar") ? jarName.substring(0, jarName.length() - 4) : jarName;
    }

    private void disableExactPlugin(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) return;
        String wanted = pluginName.trim();
        for (org.bukkit.plugin.Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p == null || p.getName() == null) continue;
            if (p.getName().equalsIgnoreCase(wanted)) {
                try {
                    getLogger().info("Disabling plugin before scheduled deletion: " + p.getName());
                    Bukkit.getPluginManager().disablePlugin(p);
                } catch (Exception ignored) {}
                break;
            }
        }
    }

    private boolean attemptDeletion(File target, int maxRetries) {
        for (int i = 0; i < maxRetries; i++) {
            try {
                java.nio.file.Files.delete(target.toPath());
                return true;
            } catch (java.nio.file.NoSuchFileException e) {
                return true; // File already gone
            } catch (Exception e) {
                getLogger().warning("Deletion attempt " + (i + 1) + "/" + maxRetries + " failed for " + target.getName() + ": " + e.getClass().getSimpleName());
                if (i < maxRetries - 1) {
                    try {
                        Thread.sleep(100); // Brief pause before retry
                    } catch (InterruptedException ignored) {}
                }
            }
        }
        return false;
    }

    private void performFinalDeletions() {
        File pendingFile;
        try {
            pendingFile = new File(getDataFolder(), "pending-deletions.txt");
        } catch (Exception e) {
            return;
        }
        if (!pendingFile.exists()) return;

        File pluginsFolder;
        try {
            pluginsFolder = getDataFolder().getParentFile();
        } catch (Exception e) {
            return;
        }
        synchronized (deletionLock) {
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(pendingFile.toPath());
            for (String line : lines) {
                String entry = line == null ? "" : line.trim();
                if (entry.isEmpty()) continue;
                if (entry.startsWith("plugin:")) continue;
                try {
                    if (entry.startsWith("jar:")) {
                        String name = entry.substring(4);
                        File target = new File(pluginsFolder, name);
                        if (target.exists()) {
                            attemptDeletion(target, 5);
                        }
                    } else if (entry.startsWith("dir:")) {
                        String dirName = entry.substring(4);
                        File targetDir = new File(pluginsFolder, dirName);
                        if (targetDir.exists()) {
                            try {
                                archiveDataFolder(targetDir, dirName);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                } catch (Exception e) {
                    // Silently continue at shutdown
                }
            }
            java.nio.file.Files.deleteIfExists(pendingFile.toPath());
        } catch (Exception ignored) {}
        }
    }
}
