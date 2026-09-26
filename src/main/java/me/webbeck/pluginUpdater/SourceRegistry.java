package me.webbeck.pluginUpdater;

import java.util.HashMap;
import java.util.Map;

/**
 * Single dispatch point for all source types. Replaces the repeated
 * {@code if type.equals(...)} chains in UpdateChecker, UpdateDownloader,
 * and CommandHandler.
 */
public final class SourceRegistry {
    private final UpdateChecker checker;
    private final Map<String, PluginSource> sources = new HashMap<>();

    public SourceRegistry(UpdateChecker checker) {
        this.checker = checker;
        register(new PluginSource() {
            public String type() { return "MODRINTH"; }
            public UpdateInfo check(PluginSnapshot s) throws Exception {
                return checker.checkModrinth(s.pluginName, s.projectId, s.currentVersion, s.allowedTypes, s.serverType, null, s.gameVersionFilter);
            }
            public Map<String, String> fetchAllChannels(PluginSnapshot s) throws Exception {
                return checker.fetchAllChannelsModrinth(s.projectId, s.serverType);
            }
        });
        register(new PluginSource() {
            public String type() { return "GITHUB"; }
            public UpdateInfo check(PluginSnapshot s) throws Exception {
                return checker.checkGitHub(s.pluginName, s.githubRepo, s.currentVersion, s.allowedTypes, s.githubAsset);
            }
            public Map<String, String> fetchAllChannels(PluginSnapshot s) throws Exception {
                return checker.fetchAllChannelsGitHub(s.githubRepo);
            }
        });
        register(new PluginSource() {
            public String type() { return "HANGAR"; }
            public UpdateInfo check(PluginSnapshot s) throws Exception {
                return checker.checkHangar(s.pluginName, s.projectId, s.currentVersion, s.allowedTypes, s.serverType);
            }
            public Map<String, String> fetchAllChannels(PluginSnapshot s) throws Exception {
                return checker.fetchAllChannelsHangar(s.projectId);
            }
        });
        register(new PluginSource() {
            public String type() { return "SPIGOT"; }
            public UpdateInfo check(PluginSnapshot s) throws Exception {
                return checker.checkSpigot(s.pluginName, s.projectId, s.currentVersion, s.lastEtag, s.lastModified);
            }
            public Map<String, String> fetchAllChannels(PluginSnapshot s) throws Exception {
                return checker.fetchAllChannelsSpigot(s.projectId);
            }
        });
        register(new PluginSource() {
            public String type() { return "CUSTOM"; }
            public UpdateInfo check(PluginSnapshot s) throws Exception {
                return checker.checkCustom(s);
            }
        });
    }

    private void register(PluginSource source) {
        sources.put(source.type().toUpperCase(), source);
    }

    public UpdateInfo check(PluginSnapshot snapshot) throws Exception {
        PluginSource source = sources.get(snapshot.type.toUpperCase());
        if (source == null) {
            throw new SourceException(SourceException.Kind.NO_SOURCE, snapshot.pluginName,
                    "unknown source type '" + snapshot.type + "'");
        }
        return source.check(snapshot);
    }

    public Map<String, String> fetchAllChannels(PluginSnapshot snapshot) throws Exception {
        PluginSource source = sources.get(snapshot.type.toUpperCase());
        if (source == null) return Map.of();
        return source.fetchAllChannels(snapshot);
    }
}
