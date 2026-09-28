# Architecture

PluginUpdater-WB is organized around five responsibilities:

- Configuration — ConfigManager owns plugin tracking state, server type resolution, and persisted configuration.
- Source resolution — SourceRegistry dispatches source-specific update discovery.
- Checking — UpdateChecker resolves candidate releases and records per-plugin failures without blocking the server thread.
- Artifact handling — UpdateDownloader handles asynchronous transfer/staging while UpdateArtifactValidator validates downloaded plugin jars and expected digests.
- Server lifecycle — PluginUpdater owns Bukkit/Paper lifecycle, scheduler boundaries, executors, and pending state.

## Update safety

Downloads are staged through a temporary file, require HTTPS, reject HTTP redirects, reject non-JAR filenames, enforce a 128 MiB transfer limit, and validate the resulting plugin descriptor before staging. Existing plugin jars are backed up before an update is staged; a backup failure aborts that update instead of continuing without recovery data.

## Threading rule

Bukkit/Paper API access is kept on the server thread. Network and filesystem work is dispatched to the I/O/download executors. Values needed by workers are snapshotted before entering asynchronous tasks.

## Compatibility target

The current release line targets Java 25 and stable Paper API 26.2. The exact API artifact is pinned in pom.xml to avoid accidentally compiling against a newer development API.