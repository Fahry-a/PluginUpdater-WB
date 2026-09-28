package me.webbeck.pluginUpdater;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateDownloader {
    private final PluginUpdater plugin;
    private final UpdateChecker updateChecker;
    private final ConfigManager configManager;

    public UpdateDownloader(PluginUpdater plugin, UpdateChecker updateChecker, ConfigManager configManager) {
        this.plugin = plugin;
        this.updateChecker = updateChecker;
        this.configManager = configManager;
    }

    private java.util.concurrent.Executor downloadExecutor() {
        try {
            IoExecutors io = plugin.getIoExecutors();
            if (io != null) return io.downloadPool();
        } catch (Exception ignored) {
        }
        return java.util.concurrent.ForkJoinPool.commonPool();
    }

    public void applyUpdates(CommandSender sender, List<UpdateInfo> updatesToApply) {
        if (updatesToApply.isEmpty()) {
            plugin.sendMsg(sender, ChatColor.RED + "No updates pending to apply.");
            return;
        }

        File updateFolder = plugin.getUpdateFolder();
        if (!updateFolder.exists()) updateFolder.mkdirs();

        File backupFolder = new File(plugin.getDataFolder(), "backups");
        if (!backupFolder.exists()) backupFolder.mkdirs();

        plugin.sendMsg(sender, ChatColor.AQUA + "Downloading " + updatesToApply.size() + " updates asynchronously...");

        // Snapshot Bukkit state on this thread; workers never touch the API off-thread.
        java.util.Map<String, File> runningJars = new java.util.HashMap<>();
        java.util.Map<String, String> exactNames = new java.util.HashMap<>();
        for (UpdateInfo info : updatesToApply) {
            try {
                Plugin runningPlugin = Bukkit.getPluginManager().getPlugin(info.pluginName);
                if (runningPlugin != null) {
                    exactNames.put(info.pluginName.toLowerCase(), runningPlugin.getName());
                    try {
                        var codeSource = runningPlugin.getClass().getProtectionDomain().getCodeSource();
                        if (codeSource != null && codeSource.getLocation() != null) {
                            File jar = new File(codeSource.getLocation().toURI());
                            if (jar.isFile()) runningJars.put(info.pluginName.toLowerCase(), jar);
                        }
                    } catch (Exception ignored) {
                    }
                } else {
                    exactNames.put(info.pluginName.toLowerCase(), info.pluginName);
                }
            } catch (Exception ignored) {
                exactNames.put(info.pluginName.toLowerCase(), info.pluginName);
            }
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (UpdateInfo info : updatesToApply) {
            if (!info.requiredDependencies.isEmpty()) {
                plugin.sendMsg(sender, ChatColor.YELLOW + "Note: " + info.pluginName + " lists dependencies: "
                        + configManager.describeModrinthIds(info.requiredDependencies));
            }

            final File runningJar = runningJars.get(info.pluginName.toLowerCase());
            final String expectedName = exactNames.getOrDefault(info.pluginName.toLowerCase(), info.pluginName);
            final boolean hasClearTarget = runningJar != null;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    if (runningJar != null) {
                        String safeBase = sanitizeFileName(info.pluginName);
                        File backupFile = new File(backupFolder, safeBase + "-" + sanitizeFileName(info.oldVersion) + ".jar");
                        Files.copy(runningJar.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        if (!backupFile.isFile()) {
                            throw new java.io.IOException("backup was not created");
                        }
                        File[] pluginBackups = backupFolder.listFiles((dir, name) ->
                                name.startsWith(safeBase + "-") && !name.endsWith("-existing.jar"));
                        if (pluginBackups != null && pluginBackups.length > 3) {
                            Arrays.sort(pluginBackups, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                            for (int i = 3; i < pluginBackups.length; i++) {
                                Files.deleteIfExists(pluginBackups[i].toPath());
                            }
                        }
                    }

                    File targetFile = new File(updateFolder, info.fileName);
                    if (targetFile.isFile() && stagedMatchesRemote(targetFile, info)) {
                        plugin.getPendingUpdates().remove(info.pluginName.toLowerCase());
                        plugin.sendMsg(sender, ChatColor.YELLOW + info.fileName + " for " + info.pluginName
                                + " is already staged - restart the server to apply it.");
                        return;
                    }
                    if (targetFile.exists()) {
                        Path backupPath = new File(backupFolder, sanitizeFileName(info.pluginName) + "-existing.jar").toPath();
                        Files.copy(targetFile.toPath(), backupPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    File downloadedFile = downloadFileToDirectory(info.downloadUrl, updateFolder, info.fileName);

                    String stagedName = validateStagedJar(sender, downloadedFile, expectedName, hasClearTarget);
                    plugin.getPendingUpdates().remove(info.pluginName.toLowerCase());
                    if (stagedName != null) {
                        persistDownloadValidators(info, downloadedFile);
                        plugin.sendMsg(sender, ChatColor.GREEN + "Staged " + downloadedFile.getName()
                                + " (plugin name: " + stagedName + ") - restart the server to apply it to " + info.pluginName + ".");
                    }
                } catch (Exception e) {
                    plugin.sendMsg(sender, ChatColor.RED + "Failed to download " + info.pluginName + ": " + e.getMessage());
                }
            }, downloadExecutor());
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenRun(() -> {
            plugin.sendMsg(sender, ChatColor.GOLD + "All requested updates downloaded! Restart server to apply.");
            updateChecker.runUpdateCheck(Bukkit.getConsoleSender(), false, null);
        });
    }

    /** Records ETag / Last-Modified / sha256 of a successful download so CUSTOM/SPIGOT stop re-downloading. */
    private void persistDownloadValidators(UpdateInfo info, File downloadedFile) {
        String sha256 = null;
        try {
            sha256 = JarHasher.sha256(downloadedFile.toPath());
        } catch (Exception ignored) {
        }
        final String finalSha = sha256;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    var sec = plugin.getPluginsConfig().getConfigurationSection(info.pluginName);
                    if (sec == null) {
                        String resolved = configManager.resolvePluginName(info.pluginName);
                        if (resolved != null) sec = plugin.getPluginsConfig().getConfigurationSection(resolved);
                    }
                    if (sec == null) return;
                    String key = sec.getName();
                    if (info.remoteEtag != null) plugin.getPluginsConfig().set(key + ".last-etag", info.remoteEtag);
                    if (info.remoteLastModified != null) plugin.getPluginsConfig().set(key + ".last-modified", info.remoteLastModified);
                    if (finalSha != null) plugin.getPluginsConfig().set(key + ".last-sha256", finalSha);
                    configManager.saveAndFormatConfigAsync();
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    public void downloadPluginToPluginsFolder(CommandSender sender, String pluginName) {
        String resolvedName = configManager.resolvePluginName(pluginName);
        if (resolvedName == null) {
            plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + pluginName + "' not found in config.");
            return;
        }

        // Copy Yaml + Bukkit state on this thread; the worker only sees the snapshot.
        PluginSnapshot snapshot = buildSnapshot(resolvedName);
        if (snapshot == null) {
            plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + resolvedName + "' not found in config.");
            return;
        }
        Plugin runningAtCall;
        try {
            runningAtCall = Bukkit.getPluginManager().getPlugin(resolvedName);
        } catch (Exception e) {
            runningAtCall = null;
        }
        final String expectedAtCall = runningAtCall != null ? runningAtCall.getName() : resolvedName;
        final boolean hasTargetAtCall = runningAtCall != null;

        plugin.sendMsg(sender, ChatColor.AQUA + "Downloading " + resolvedName + " into the plugins folder...");

        CompletableFuture.runAsync(() -> {
            try {
                UpdateInfo info = updateChecker.getRegistrySnapshotCheck(snapshot);

                if (info == null) {
                    plugin.sendMsg(sender, ChatColor.RED + "Could not determine a downloadable release for " + resolvedName + ".");
                    return;
                }

                File pluginsFolder = plugin.getDataFolder().getParentFile();
                if (!pluginsFolder.exists()) {
                    pluginsFolder.mkdirs();
                }

                File updateFolder = plugin.getUpdateFolder();
                if (!updateFolder.exists()) updateFolder.mkdirs();

                File targetFile = new File(pluginsFolder, info.fileName);
                File backupFolder = new File(plugin.getDataFolder(), "backups");
                if (!backupFolder.exists()) backupFolder.mkdirs();

                File downloadedFile;
                if (targetFile.exists()) {
                    downloadedFile = downloadFileToDirectory(info.downloadUrl, updateFolder, info.fileName);
                    String stagedName = validateStagedJar(sender, downloadedFile, expectedAtCall, hasTargetAtCall);
                    if (stagedName != null) {
                        persistDownloadValidators(info, downloadedFile);
                        plugin.sendMsg(sender, ChatColor.GREEN + "Staged " + downloadedFile.getName()
                                + " (plugin name: " + stagedName + ") in the update folder. Restart server to apply.");
                    }
                } else {
                    // Otherwise download directly to plugins folder
                    downloadedFile = downloadFileToDirectory(info.downloadUrl, pluginsFolder, info.fileName);
                    JarInspector.Inspection inspection = JarInspector.inspect(downloadedFile);
                    if (!inspection.valid) {
                        deleteQuietly(downloadedFile);
                        plugin.sendMsg(sender, ChatColor.RED + "Rejected " + downloadedFile.getName() + ": " + inspection.error);
                    } else {
                        persistDownloadValidators(info, downloadedFile);
                        plugin.sendMsg(sender, ChatColor.GREEN + "Downloaded " + resolvedName + " to plugins folder as " + downloadedFile.getName() + ". Restart server to load it.");
                    }
                }
            } catch (Exception e) {
                plugin.sendMsg(sender, ChatColor.RED + "Failed to download plugin to plugins folder: " + e.getMessage());
            }
        }, downloadExecutor());
    }

    private PluginSnapshot buildSnapshot(String resolvedName) {
        try {
            var sec = plugin.getPluginsConfig().getConfigurationSection(resolvedName);
            if (sec == null) return null;
            List<String> allowedTypes = new ArrayList<>(sec.getStringList("allowed-release-types"));
            if (allowedTypes.isEmpty() || allowedTypes.contains("all") || allowedTypes.contains("ALL")) {
                allowedTypes = Arrays.asList("release", "beta", "alpha", "prerelease");
            }
            Plugin running = null;
            String currentVer = sec.getString("current-version", "0.0.0");
            File runningJar = null;
            try {
                running = Bukkit.getPluginManager().getPlugin(resolvedName);
                if (running != null) {
                    currentVer = running.getDescription().getVersion();
                    var cs = running.getClass().getProtectionDomain().getCodeSource();
                    if (cs != null && cs.getLocation() != null) {
                        File jar = new File(cs.getLocation().toURI());
                        if (jar.isFile()) runningJar = jar;
                    }
                }
            } catch (Exception ignored) {
            }
            return new PluginSnapshot(resolvedName, sec.getString("type", "MODRINTH"),
                    sec.getString("project-id"), sec.getString("github-repo"), sec.getString("custom-url"),
                    allowedTypes, currentVer, configManager.getPluginServerType(resolvedName),
                    configManager.getMinecraftVersion(), runningJar, currentVer,
                    sec.getString("expected-sha1"), sec.getString("expected-sha256"),
                    sec.getString("last-etag"), sec.getString("last-modified"),
                    sec.getBoolean("game-version-filter", true), sec.getString("github-asset", null));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the plugin name inside the jar when it is safe to stage, or null when the
     * file was rejected (and deleted). An invalid file is never left in the update folder.
     */
    private String validateStagedJar(CommandSender sender, File downloadedFile, String expectedName, boolean hasClearTarget) {
        UpdateArtifactValidator.Validation validation =
                UpdateArtifactValidator.validate(downloadedFile, expectedName, hasClearTarget);
        if (!validation.valid()) {
            deleteQuietly(downloadedFile);
            plugin.sendMsg(sender, ChatColor.RED + "Rejected " + downloadedFile.getName() + " for " + expectedName
                    + ": " + validation.error());
            return null;
        }
        if (validation.warning() != null) {
            plugin.sendMsg(sender, ChatColor.YELLOW + validation.warning());
        }
        return validation.pluginName();
    }

    private static boolean stagedMatchesRemote(File stagedFile, UpdateInfo info) {
        return UpdateArtifactValidator.matchesExpectedDigest(stagedFile, info.expectedSha1, info.expectedSha256);
    }

    private static String sanitizeFileName(String name) {
        if (name == null) return "unknown";
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "unknown" : safe;
    }

    private static void deleteQuietly(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (Exception ignored) {
        }
    }

    public void showStagedUpdates(CommandSender sender) {
        File updateFolder = plugin.getUpdateFolder();

        java.util.Map<String, String> installedJars = new java.util.HashMap<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p == null || p.getName() == null) continue;
            String jarName = null;
            try {
                var codeSource = p.getClass().getProtectionDomain().getCodeSource();
                if (codeSource != null && codeSource.getLocation() != null) {
                    jarName = new File(codeSource.getLocation().toURI()).getName();
                }
            } catch (Exception ignored) {
            }
            installedJars.put(p.getName(), jarName);
        }

        File[] files = updateFolder.isDirectory() ? updateFolder.listFiles(File::isFile) : null;
        if (files == null || files.length == 0) {
            plugin.sendMsg(sender, ChatColor.GREEN + "Update folder is empty - nothing staged.");
            return;
        }
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        plugin.sendMsg(sender, ChatColor.GOLD + "=== Staged Updates (" + files.length + ") ===");
        java.util.Map<String, Integer> nameCounts = new java.util.HashMap<>();
        int i = 1;
        for (File f : files) {
            String name = f.getName();
            if (name.endsWith(".download.tmp") || name.endsWith(".tmp")) {
                plugin.sendMsg(sender, ChatColor.GRAY + "" + (i++) + ". " + name + " - leftover from a failed download, safe to delete.");
                continue;
            }
            JarInspector.Inspection inspection = JarInspector.inspect(f);
            if (!inspection.valid) {
                plugin.sendMsg(sender, ChatColor.RED + "" + (i++) + ". " + name + " - " + inspection.error + "; Paper will ignore this file.");
                continue;
            }
            nameCounts.merge(inspection.pluginName, 1, Integer::sum);
            String installedJar = installedJars.get(inspection.pluginName);
            if (installedJar != null) {
                plugin.sendMsg(sender, ChatColor.GREEN + "" + (i++) + ". " + name
                        + ChatColor.GRAY + " - name: " + inspection.pluginName + ", matches plugins/" + installedJar);
            } else {
                plugin.sendMsg(sender, ChatColor.RED + "" + (i++) + ". " + name
                        + ChatColor.GRAY + " - name: " + inspection.pluginName + ", but NO installed plugin has this name; Paper will ignore it.");
            }
        }
        for (java.util.Map.Entry<String, Integer> entry : nameCounts.entrySet()) {
            if (entry.getValue() > 1) {
                plugin.sendMsg(sender, ChatColor.YELLOW + "Warning: " + entry.getValue()
                        + " staged files report name '" + entry.getKey() + "' - only the first applies, the rest are stuck.");
            }
        }
    }

    private File downloadFileToDirectory(String downloadUrl, File directory, String fallbackName) throws Exception {
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new IllegalArgumentException("download URL is empty");
        }
        java.net.URI uri = java.net.URI.create(downloadUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new SecurityException("only HTTPS download URLs are allowed");
        }
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new java.io.IOException("could not create download directory");
        }

        final long maxBytes = 128L * 1024L * 1024L;
        String safeFallback = sanitizeFileName(fallbackName);
        Path directoryPath = directory.toPath().toAbsolutePath().normalize();
        Path tempPath = directoryPath.resolve(safeFallback + ".download.tmp").normalize();
        if (!tempPath.startsWith(directoryPath)) {
            throw new SecurityException("invalid temporary download path");
        }

        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri)
                    .timeout(java.time.Duration.ofSeconds(60))
                    .header("User-Agent", "PluginUpdater-WB/26.2")
                    .build();

            java.net.http.HttpResponse<java.io.InputStream> response = plugin.getHttpClient().send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();
            if (status < 200 || status > 299) {
                response.body().close();
                throw new java.io.IOException("server returned HTTP " + status);
            }
            if (!"https".equalsIgnoreCase(response.uri().getScheme())) {
                response.body().close();
                throw new SecurityException("download redirected to a non-HTTPS URL");
            }

            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > maxBytes) {
                response.body().close();
                throw new java.io.IOException("download exceeds the 128 MiB safety limit");
            }

            try (java.io.InputStream input = response.body();
                 java.io.OutputStream output = Files.newOutputStream(tempPath,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                long total = 0L;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        throw new java.io.IOException("download exceeds the 128 MiB safety limit");
                    }
                    output.write(buffer, 0, read);
                }
            }

            String actualName = sanitizeFileName(extractFileNameFromResponse(response, safeFallback));
            Path resultPath = directoryPath.resolve(actualName).normalize();
            if (!resultPath.startsWith(directoryPath)) {
                throw new SecurityException("invalid download filename");
            }
            if (!actualName.toLowerCase().endsWith(".jar")) {
                throw new java.io.IOException("downloaded artifact is not a .jar file");
            }

            if (!resultPath.equals(tempPath)) {
                Files.move(tempPath, resultPath, StandardCopyOption.REPLACE_EXISTING);
            }
            return resultPath.toFile();
        } catch (Exception e) {
            try { Files.deleteIfExists(tempPath); } catch (Exception ignored) {}
            throw e;
        }
    }
    private String extractFileNameFromResponse(java.net.http.HttpResponse<?> response, String fallbackName) {
        var contentDisposition = response.headers().firstValue("Content-Disposition");
        if (contentDisposition.isPresent()) {
            Matcher matcher = Pattern.compile("filename\\*?=(?:UTF-8''?)?\"?([^\";]+)\"?").matcher(contentDisposition.get());
            if (matcher.find()) {
                String filename = matcher.group(1).trim();
                if (!filename.isEmpty()) {
                    return new File(filename).getName();
                }
            }
        }

        String path = response.uri().getPath();
        if (path != null && path.toLowerCase().endsWith(".jar")) {
            return new File(path).getName();
        }

        return fallbackName;
    }

    public void performRollback(CommandSender sender, String pluginName, String fileName) {
        File backupFolder = new File(plugin.getDataFolder(), "backups");
        File backupFile = new File(backupFolder, fileName);
        if (!backupFile.exists()) {
            plugin.sendMsg(sender, ChatColor.RED + "Backup file not found!");
            return;
        }

        File updateFolder = plugin.getUpdateFolder();
        if (!updateFolder.exists()) updateFolder.mkdirs();

        CompletableFuture.runAsync(() -> {
            try {
                Files.copy(backupFile.toPath(), new File(updateFolder, fileName).toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.sendMsg(sender, ChatColor.GREEN + "Rollback staged! " + fileName + " placed in update folder. Restart to apply.");
            } catch (Exception e) {
                plugin.sendMsg(sender, ChatColor.RED + "Failed to stage rollback: " + e.getMessage());
            }
        }, downloadExecutor());
    }

    public void forceRedownload(CommandSender sender, String pluginName) {
        String resolvedName = configManager.resolvePluginName(pluginName);
        if (resolvedName == null) {
            plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + pluginName + "' not found in config.");
            return;
        }

        PluginSnapshot snapshot = buildSnapshot(resolvedName);
        if (snapshot == null) {
            plugin.sendMsg(sender, ChatColor.RED + "Plugin '" + resolvedName + "' not found in config.");
            return;
        }

        plugin.sendMsg(sender, ChatColor.AQUA + "Fetching latest version data for " + resolvedName + " to redownload...");

        CompletableFuture.runAsync(() -> {
            try {
                UpdateInfo info = updateChecker.getRegistrySnapshotCheck(snapshot);

                if (info != null) {
                    applyUpdates(sender, Collections.singletonList(info));
                } else {
                    plugin.sendMsg(sender, ChatColor.RED + "Could not find any valid releases for " + resolvedName + " to redownload.");
                }
            } catch (Exception e) {
                plugin.sendMsg(sender, ChatColor.RED + "Failed to fetch data for " + resolvedName + ": " + e.getMessage());
            }
        }, downloadExecutor());
    }
}