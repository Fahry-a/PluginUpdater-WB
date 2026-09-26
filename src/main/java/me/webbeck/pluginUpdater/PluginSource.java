package me.webbeck.pluginUpdater;

import java.util.Map;

/**
 * One update source (Modrinth, GitHub, Hangar, Spigot, Custom).
 * Implementations are pure network + parsing; they receive a
 * {@link PluginSnapshot} so they never touch Bukkit off-thread.
 */
public interface PluginSource {
    String type();

    UpdateInfo check(PluginSnapshot snapshot) throws Exception;

    default Map<String, String> fetchAllChannels(PluginSnapshot snapshot) throws Exception {
        return Map.of();
    }
}
