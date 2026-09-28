package me.webbeck.pluginUpdater;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Manages the two Geyser-ecosystem artifacts that cannot be regular plugins:
 *
 * <ul>
 *   <li><b>Floodgate</b> — its Spigot jar exists on no versioned API (the Modrinth
 *   project {@code bWrNNfkb} is Fabric/NeoForge only, GitHub has no releases), so
 *   it is a direct download. Up-to-date checks use HEAD ETag/Last-Modified.</li>
 *   <li><b>MCXboxBroadcast</b> — a Geyser <em>extension</em>, not a plugin, tracked
 *   via GitHub releases with an explicit asset pin (the release also ships a
 *   Standalone jar, so fuzzy matching refuses to choose).</li>
 * </ul>
 *
 * <p>Geyser itself is NOT handled here: it is a regular plugin tracked on
 * Modrinth ({@code wKkoqHrH}) through the normal check pipeline.
 */
public class GeyserManager {
    private final PluginUpdater plugin;
    private final HttpClient httpClient;
    private final UpdateChecker updateChecker;

    public GeyserManager(PluginUpdater plugin, HttpClient httpClient, UpdateChecker updateChecker) {
        this.plugin = plugin;
        this.httpClient = httpClient;
        this.updateChecker = updateChecker;
    }

    private java.util.concurrent.Executor downloadExecutor() {
        try {
            IoExecutors io = plugin.getIoExecutors();
            if (io != null) return io.downloadPool();
        } catch (Exception ignored) {
        }
        return java.util.concurrent.ForkJoinPool.commonPool();
    }

    public record GeyserAddon(String name, String fileName, boolean isExtension) {
    }

    /** Definitions for the two managed addons; null for anything else (incl. Geyser). */
    public GeyserAddon addonDef(String addonName) {
        var cfg = plugin.getConfig();
        if (addonName.equalsIgnoreCase("Floodgate")) {
            return new GeyserAddon("Floodgate",
                    cfg.getString("geyser-addons.Floodgate-file", "floodgate.jar"), false);
        } else if (addonName.equalsIgnoreCase("MCXboxBroadcast")) {
            return new GeyserAddon("MCXboxBroadcast",
                    cfg.getString("geyser-addons.MCXboxBroadcast-file", "MCXboxBroadcastExtension.jar"), true);
        }
        return null;
    }

    public String floodgateUrl() {
        return plugin.getConfig().getString("geyser-addons.Floodgate-url",
                "https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot");
    }

    public String mcxbRepo() {
        return plugin.getConfig().getString("geyser-addons.MCXboxBroadcast-repo", "MCXboxBroadcast/Broadcaster");
    }

    public String mcxbAsset() {
        return plugin.getConfig().getString("geyser-addons.MCXboxBroadcast-asset", "MCXboxBroadcastExtension.jar");
    }

    public void displayGeyserList(CommandSender sender) {
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.GOLD + "=== Geyser Addons Management ==="));
        String[] addons = {"Floodgate", "MCXboxBroadcast"};

        Component downloadAllBtn = Component.text("[DOWNLOAD MISSING]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/upd plugin geyser download all"))
                .hoverEvent(HoverEvent.showText(Component.text("Download any missing Geyser addons", NamedTextColor.YELLOW)));
        Component updateAllBtn = Component.text(" [UPDATE ALL]", NamedTextColor.LIGHT_PURPLE)
                .clickEvent(ClickEvent.runCommand("/upd plugin geyser update all"))
                .hoverEvent(HoverEvent.showText(Component.text("Force download latest versions for all Geyser addons", NamedTextColor.GOLD)));
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.GRAY + "Actions: ").append(downloadAllBtn).append(updateAllBtn));

        for (String addon : addons) {
            boolean isEnabled = plugin.getConfig().getBoolean("geyser-addons." + addon, true);
            boolean downloaded = isGeyserAddonDownloaded(addon);

            Component tc = Component.text("- " + addon + " ", NamedTextColor.AQUA)
                    .append(Component.text(downloaded ? "(downloaded) " : "(missing) ", NamedTextColor.GRAY));

            Component toggleBtn;
            if (isEnabled) {
                toggleBtn = Component.text("[DISABLE]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/upd plugin geyser toggle " + addon + " false"))
                        .hoverEvent(HoverEvent.showText(Component.text("Disable updates for " + addon, NamedTextColor.YELLOW)));
            } else {
                toggleBtn = Component.text("[ENABLE]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/upd plugin geyser toggle " + addon + " true"))
                        .hoverEvent(HoverEvent.showText(Component.text("Enable updates for " + addon, NamedTextColor.YELLOW)));
            }

            Component updateBtn = Component.text(" [UPDATE]", NamedTextColor.LIGHT_PURPLE)
                    .clickEvent(ClickEvent.runCommand("/upd plugin geyser update " + addon))
                    .hoverEvent(HoverEvent.showText(Component.text("Force download latest " + addon, NamedTextColor.GOLD)));

            if (isEnabled) {
                sender.sendMessage(tc.append(toggleBtn).append(updateBtn));
            } else {
                sender.sendMessage(tc.append(toggleBtn));
            }
        }
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(
                ChatColor.GRAY + "Geyser itself is tracked as a regular plugin (Modrinth) - see /upd list."));
    }

    public void setGeyserSupport(CommandSender sender, boolean enabled) {
        plugin.getConfig().set("geyser-addons.enabled", enabled);
        plugin.saveConfig();
        plugin.sendMsg(sender, ChatColor.GREEN + "Geyser addon management is now " + (enabled ? "ENABLED" : "DISABLED") + ".");
        if (enabled) {
            displayGeyserList(sender);
        }
    }

    public File getGeyserAddonDestination(String addonName) {
        GeyserAddon def = addonDef(addonName);
        if (def == null) return null;
        File updateFolder = plugin.getUpdateFolder();
        if (!updateFolder.exists()) updateFolder.mkdirs();

        if (!def.isExtension()) {
            return new File(updateFolder, def.fileName());
        } else {
            File extFolder = new File(plugin.getDataFolder().getParentFile(), "Geyser-Spigot/extensions");
            if (!extFolder.exists()) extFolder.mkdirs();
            return new File(extFolder, def.fileName());
        }
    }

    public boolean isGeyserAddonDownloaded(String addonName) {
        GeyserAddon def = addonDef(addonName);
        if (def == null) return false;
        File pluginsDir = plugin.getDataFolder().getParentFile();
        File updateFolder = plugin.getUpdateFolder();

        if (!def.isExtension()) {
            File installed = new File(pluginsDir, def.fileName());
            File staged = new File(updateFolder, def.fileName());
            return installed.exists() || staged.exists();
        } else {
            File extFolder = new File(pluginsDir, "Geyser-Spigot/extensions");
            File installed = new File(extFolder, def.fileName());
            File staged = new File(updateFolder, def.fileName());
            return installed.exists() || staged.exists();
        }
    }

    public void downloadGeyserAddon(CommandSender sender, String addonName, boolean force) {
        if (addonName.equalsIgnoreCase("Geyser")) {
            plugin.sendMsg(sender, ChatColor.YELLOW + "Geyser is tracked as a regular plugin via Modrinth - see /upd list.");
            return;
        }
        if (addonName.equalsIgnoreCase("Floodgate")) {
            downloadFloodgate(sender, force);
            return;
        }
        if (addonName.equalsIgnoreCase("MCXboxBroadcast")) {
            downloadMcxb(sender, force);
            return;
        }
        plugin.sendMsg(sender, ChatColor.RED + "Unknown addon '" + addonName + "'. Managed addons: Floodgate, MCXboxBroadcast.");
    }

    private void downloadFloodgate(CommandSender sender, boolean force) {
        GeyserAddon def = addonDef("Floodgate");
        String url = floodgateUrl();
        File dest = getGeyserAddonDestination("Floodgate");
        if (def == null || dest == null) return;

        plugin.sendMsg(sender, ChatColor.AQUA + (force ? "Updating " : "Downloading ") + "Floodgate...");

        CompletableFuture.runAsync(() -> {
            try {
                // No version API exists for this URL; HEAD validators are the only
                // change signal. Stored after every successful download.
                String[] remote = updateChecker.probeRemoteValidators(url);
                String storedEtag = plugin.getConfig().getString("geyser-addons.Floodgate-etag", null);
                String storedMod = plugin.getConfig().getString("geyser-addons.Floodgate-last-modified", null);

                if (!force && validatorsMatch(remote[0], remote[1], storedEtag, storedMod)) {
                    plugin.sendMsg(sender, ChatColor.GREEN + "Floodgate is already up to date.");
                    return;
                }
                if (!force && dest.exists() && storedEtag == null && storedMod == null
                        && (remote[0] == null && remote[1] == null)) {
                    plugin.sendMsg(sender, ChatColor.YELLOW + "Floodgate is already downloaded.");
                    return;
                }

                File tempFile = downloadToTemp(url, dest);
                try {
                    JarInspector.Inspection inspection = JarInspector.inspect(tempFile.toPath());
                    if (!inspection.valid) {
                        throw new java.io.IOException("downloaded file rejected: " + inspection.error);
                    }
                    backupExisting(dest, def.fileName());
                    java.nio.file.Files.move(tempFile.toPath(), dest.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    persistGeyserState("Floodgate-etag", remote[0], "Floodgate-last-modified", remote[1]);
                    plugin.sendMsg(sender, ChatColor.GREEN + "Successfully downloaded Floodgate"
                            + " to update/ (restart server to apply)!");
                } finally {
                    try {
                        java.nio.file.Files.deleteIfExists(tempFile.toPath());
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                plugin.sendMsg(sender, ChatColor.RED + "Failed to download Floodgate: " + e.getMessage());
            }
        }, downloadExecutor());
    }

    private void downloadMcxb(CommandSender sender, boolean force) {
        GeyserAddon def = addonDef("MCXboxBroadcast");
        File dest = getGeyserAddonDestination("MCXboxBroadcast");
        if (def == null || dest == null) return;
        String repo = mcxbRepo();
        String asset = mcxbAsset();
        String storedVersion = plugin.getConfig().getString("geyser-addons.MCXboxBroadcast-version", null);

        plugin.sendMsg(sender, ChatColor.AQUA + (force ? "Updating " : "Downloading ") + "MCXboxBroadcast...");

        CompletableFuture.runAsync(() -> {
            try {
                UpdateInfo info = updateChecker.checkGitHub("MCXboxBroadcast", repo,
                        storedVersion != null ? storedVersion : "0", List.of("release"), asset);
                if (info == null) {
                    plugin.sendMsg(sender, ChatColor.RED + "No GitHub release of " + repo
                            + " contains asset '" + asset + "'.");
                    return;
                }
                if (!force && storedVersion != null && storedVersion.equals(info.newVersion)
                        && (dest.exists() || isGeyserAddonDownloaded("MCXboxBroadcast"))) {
                    plugin.sendMsg(sender, ChatColor.GREEN + "MCXboxBroadcast is already up to date (build "
                            + info.newVersion + ").");
                    return;
                }

                File tempFile = downloadToTemp(info.downloadUrl, dest);
                try {
                    JarInspector.Inspection inspection = JarInspector.inspectExtension(tempFile.toPath());
                    if (!inspection.valid) {
                        throw new java.io.IOException("downloaded file rejected: " + inspection.error);
                    }
                    if (info.expectedSha256 != null && !info.expectedSha256.isBlank()) {
                        String actual;
                        try {
                            actual = JarHasher.sha256(tempFile.toPath());
                        } catch (Exception e) {
                            throw new java.io.IOException("could not hash download: " + e.getMessage());
                        }
                        if (!info.expectedSha256.equalsIgnoreCase(actual)) {
                            throw new java.io.IOException("sha256 mismatch: expected "
                                    + info.expectedSha256 + " but got " + actual);
                        }
                    }
                    backupExisting(dest, def.fileName());
                    java.nio.file.Files.move(tempFile.toPath(), dest.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    persistGeyserState("MCXboxBroadcast-version", info.newVersion, null, null);
                    plugin.sendMsg(sender, ChatColor.GREEN + "Successfully downloaded MCXboxBroadcast (build "
                            + info.newVersion + ") directly to extensions/ (restart Geyser to load).");
                } finally {
                    try {
                        java.nio.file.Files.deleteIfExists(tempFile.toPath());
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                plugin.sendMsg(sender, ChatColor.RED + "Failed to download MCXboxBroadcast: " + e.getMessage());
            }
        }, downloadExecutor());
    }

    private File downloadToTemp(String url, File dest) throws Exception {
        File tempFile = new File(dest.getParentFile(), dest.getName() + ".download.jar");
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("User-Agent", "PluginUpdater-WB").build();
        HttpResponse<java.nio.file.Path> response = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(tempFile.toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
        int status = response.statusCode();
        if (status < 200 || status > 299) {
            try {
                java.nio.file.Files.deleteIfExists(tempFile.toPath());
            } catch (Exception ignored) {
            }
            throw new java.io.IOException("server returned HTTP " + status);
        }
        return tempFile;
    }

    private void backupExisting(File dest, String fileName) {
        if (!dest.exists()) return;
        try {
            File backupFolder = new File(plugin.getDataFolder(), "backups");
            if (!backupFolder.exists()) backupFolder.mkdirs();
            java.nio.file.Files.copy(dest.toPath(),
                    new File(backupFolder, fileName + ".bak").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    /** Config mutation on the main thread; the worker never touches Yaml off-thread. */
    private void persistGeyserState(String key1, String value1, String key2, String value2) {
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (value1 != null) plugin.getConfig().set("geyser-addons." + key1, value1);
                    if (key2 != null && value2 != null) plugin.getConfig().set("geyser-addons." + key2, value2);
                    plugin.saveConfig();
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    static boolean validatorsMatch(String remoteEtag, String remoteMod, String storedEtag, String storedMod) {
        if (remoteEtag != null && storedEtag != null) return remoteEtag.equals(storedEtag);
        if (remoteMod != null && storedMod != null) return remoteMod.equals(storedMod);
        return false;
    }

    public void downloadAllGeyserAddons(CommandSender sender, boolean force) {
        String[] addons = {"Floodgate", "MCXboxBroadcast"};
        for (String addon : addons) {
            downloadGeyserAddon(sender, addon, force);
        }
    }
}
